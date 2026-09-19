package com.novamall.seckill.service;

import com.novamall.common.annotation.RateLimit;
import com.novamall.common.annotation.LimitScope;
import com.novamall.common.context.UserContext;
import com.novamall.common.exception.BizException;
import com.novamall.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务。
 *
 * <h3>整体架构（经典秒杀方案）</h3>
 * <pre>
 *   用户点击
 *     │
 *     ├─ ① 网关/接口限流（令牌桶）─────────→ 拦截掉 90% 的无效流量
 *     │
 *     ├─ ② Redis Lua 原子扣库存 ──────────→ 内存操作，微秒级，防超卖 + 防重复买
 *     │      返回 1=成功 0=售罄 -2=买过
 *     │
 *     ├─ ③ 成功后发 MQ（削峰）────────────→ 请求立刻返回，不让用户等数据库
 *     │
 *     └─ ④ 消费者异步创建订单 ────────────→ 按数据库能承受的速度慢慢消费
 *            └─ 前端拿 seckillToken 轮询结果
 * </pre>
 *
 * <h3>为什么不能同步写数据库？</h3>
 * <p>MySQL 单行 UPDATE 的 TPS 大约几千，而秒杀瞬间可能有几十万请求。
 * 同步写库会让所有请求都堵在数据库连接池上，RT 从毫秒飙到秒级，最终连接池耗尽、整个服务不可用。
 * 用 MQ 削峰：请求进来只做 Redis 操作（几万 QPS 没问题），
 * 然后按数据库能承受的速度异步落库，这就是"削峰填谷"。</p>
 *
 * <h3>几个必须回答的追问</h3>
 * <ul>
 *   <li><b>Redis 扣减成功但 MQ 挂了怎么办？</b>
 *       和下单一样，消息先落本地消息表/或用 confirm 回调 + 重试；
 *       实在发不出去就走"库存回补 + 提示用户稍后再试"，宁可少卖不能错卖。</li>
 *   <li><b>Redis 挂了怎么办？</b>
 *       秒杀本来就是"少卖可接受"的场景，降级为直接返回"活动太火爆"，
 *       绝不能在 Redis 挂掉时把流量直接放到数据库上。</li>
 *   <li><b>怎么防脚本刷？</b>
 *       ① 接口地址隐藏（下单前先拿一个一次性的秒杀地址）
 *       ② 图形验证码 / 滑块
 *       ③ 按用户维度限流（本项目已实现）
 *       ④ 风控系统识别异常 IP / 设备指纹</li>
 *   <li><b>库存怎么预热？</b>
 *       活动开始前由定时任务把 DB 库存同步到 Redis，
 *       并且要防止重复预热（用 setIfAbsent 做分布式幂等）。</li>
 * </ul>
 *
 * @author NovaMall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final SeckillLuaScripts seckillLuaScripts;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    public static final String STOCK_KEY = "seckill:stock:";
    public static final String BOUGHT_KEY = "seckill:bought:";
    public static final String ORDER_EXCHANGE = "novamall.order.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill.create";

    /**
     * 执行秒杀。
     *
     * <p>@RateLimit 按用户维度限流：每个用户每秒最多 5 次。
     * 注意这里的维度是 USER 而不是 IP——公司/学校出口 IP 是共享的，
     * 按 IP 限流会把同一出口的几百个正常用户一起误伤。</p>
     */
    @RateLimit(resource = "seckill", scope = LimitScope.USER,
            permitsPerSecond = 5, capacity = 10, message = "点击太快了，请稍后再试")
    public Map<String, Object> seckill(Long activityId, Long productId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }

        // ---------- 1. 活动是否开始（读本地缓存/Redis，不走数据库） ----------
        Boolean started = redisTemplate.hasKey("seckill:activity:" + activityId + ":started");
        if (!Boolean.TRUE.equals(started)) {
            throw new BizException(ResultCode.SECKILL_NOT_START);
        }

        String stockKey = STOCK_KEY + activityId + ":" + productId;
        String boughtKey = BOUGHT_KEY + activityId + ":" + productId;

        // ---------- 2. Redis Lua 原子扣减 ----------
        long result = seckillLuaScripts.executeSeckill(stockKey, boughtKey, String.valueOf(userId));

        Map<String, Object> resp = new HashMap<>();
        switch ((int) result) {
            case 1 -> {
                // 抢到了：生成凭证，发 MQ 异步下单
                String token = UUID.randomUUID().toString().replace("-", "");
                redisTemplate.opsForValue().set("seckill:token:" + token,
                        userId + ":" + productId, Duration.ofMinutes(5));

                Map<String, Object> msg = new HashMap<>();
                msg.put("token", token);
                msg.put("userId", userId);
                msg.put("productId", productId);
                msg.put("activityId", activityId);
                try {
                    rabbitTemplate.convertAndSend(ORDER_EXCHANGE, SECKILL_ROUTING_KEY, msg);
                } catch (Exception e) {
                    // MQ 挂了：必须把库存还回去，否则用户抢到了却没订单
                    log.error("[Seckill] MQ 发送失败，回补库存 activityId={} productId={} userId={}",
                            activityId, productId, userId, e);
                    seckillLuaScripts.executeRestore(stockKey, boughtKey, String.valueOf(userId));
                    throw new BizException(ResultCode.ORDER_CREATE_FAILED);
                }

                resp.put("success", true);
                resp.put("token", token);
                resp.put("message", "抢购成功，正在生成订单");
            }
            case 0 -> throw new BizException(ResultCode.SECKILL_SOLD_OUT);
            case -1 -> throw new BizException(ResultCode.SECKILL_NOT_START.getCode(), "活动未开始或已结束");
            case -2 -> throw new BizException(ResultCode.REPEAT_SUBMIT.getCode(), "每人限购一件哦");
            default -> throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        return resp;
    }

    /** 查询秒杀结果（前端拿 token 轮询） */
    public Map<String, Object> queryResult(String token) {
        Map<String, Object> resp = new HashMap<>();
        Object orderNo = redisTemplate.opsForValue().get("seckill:result:" + token);
        if (orderNo != null) {
            resp.put("status", "SUCCESS");
            resp.put("orderNo", String.valueOf(orderNo));
        } else {
            resp.put("status", "PROCESSING");
        }
        return resp;
    }

    /**
     * 预热库存。
     *
     * <p>为什么要用 setIfAbsent 而不是直接 set？
     * 定时任务可能在多个实例上同时跑，直接 set 会互相覆盖导致库存被重置。
     * setIfAbsent 保证只有第一个实例能预热成功。</p>
     */
    public boolean warmUp(Long activityId, Long productId, int stock) {
        String stockKey = STOCK_KEY + activityId + ":" + productId;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(stockKey, stock, Duration.ofHours(2));
        if (Boolean.TRUE.equals(ok)) {
            log.info("[Seckill] 库存预热成功 activityId={} productId={} stock={}", activityId, productId, stock);
        } else {
            log.info("[Seckill] 库存已存在，跳过预热 activityId={} productId={}", activityId, productId);
        }
        return Boolean.TRUE.equals(ok);
    }

    /** 开启活动 */
    public void startActivity(Long activityId, long durationSeconds) {
        redisTemplate.opsForValue().set("seckill:activity:" + activityId + ":started",
                "1", Duration.ofSeconds(durationSeconds));
    }

    /** 剩余库存查询（高频，走 Redis） */
    public Integer remaining(Long activityId, Long productId) {
        return seckillLuaScripts.remainingStock(STOCK_KEY + activityId + ":" + productId);
    }

    /** 订单超时未支付 → 回补库存 */
    public void restore(Long activityId, Long productId, Long userId) {
        seckillLuaScripts.executeRestore(
                STOCK_KEY + activityId + ":" + productId,
                BOUGHT_KEY + activityId + ":" + productId,
                String.valueOf(userId));
    }
}
