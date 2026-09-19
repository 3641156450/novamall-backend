package com.novamall.seckill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 秒杀 Lua 脚本集中管理。
 *
 * <h3>为什么秒杀一定要用 Lua？</h3>
 * <p>秒杀扣库存要做三件事：判断库存够不够 → 判断用户有没有买过 → 扣库存 + 记录用户。
 * 如果拆成多条 Redis 命令：
 * <ol>
 *   <li>GET 库存 = 1，准备扣</li>
 *   <li>此时另一个请求也 GET 到 1，也准备扣</li>
 *   <li>两个都 DECR，库存变成 -1 → <b>超卖</b></li>
 * </ol>
 * Redis 是单线程执行 Lua 的，把这三步写进一个脚本就天然原子了，
 * 而且只需要一次网络往返（比 4 条命令快 4 倍）。</p>
 *
 * <h3>为什么不用分布式锁？</h3>
 * <p>用锁也能保证原子，但锁是<b>串行</b>的：一万个请求要排一万个队，
 * QPS 直接掉到几百。而 Lua 脚本是"并发进来但每个都极快"，
 * 实测 QPS 能到 Redis 单实例上限（几万）。这是秒杀场景的核心取舍。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
public class SeckillLuaScripts {

    /**
     * 秒杀主流程脚本。
     *
     * <pre>
     * KEYS[1] = 库存 key        seckill:stock:{activityId}:{productId}
     * KEYS[2] = 已购用户集合     seckill:bought:{activityId}:{productId}
     * ARGV[1] = userId
     *
     * 返回值：
     *   1  = 秒杀成功
     *   0  = 已售罄
     *  -1  = 活动不存在（库存 key 没有预热）
     *  -2  = 重复购买
     * </pre>
     */
    public static final String SECKILL_SCRIPT =
            "local stockKey = KEYS[1]                                   " +
                    "local boughtKey = KEYS[2]                                 " +
                    "local userId = ARGV[1]                                    " +
                    "                                                          " +
                    "if redis.call('exists', stockKey) == 0 then               " +
                    "  return -1                                               " +
                    "end                                                       " +
                    "                                                          " +
                    "local stock = tonumber(redis.call('get', stockKey))       " +
                    "if stock == nil or stock <= 0 then                        " +
                    "  return 0                                                " +
                    "end                                                       " +
                    "                                                          " +
                    "-- 一人一单：Set 去重，O(1) 判断                            " +
                    "if redis.call('sismember', boughtKey, userId) == 1 then   " +
                    "  return -2                                               " +
                    "end                                                       " +
                    "                                                          " +
                    "redis.call('decrby', stockKey, 1)                         " +
                    "redis.call('sadd', boughtKey, userId)                     " +
                    "return 1                                                  ";

    /**
     * 库存回补脚本（订单超时未支付 / 用户取消）。
     *
     * <p>必须同时把用户从"已购集合"里移除，否则用户取消后再也买不了。</p>
     */
    public static final String RESTORE_SCRIPT =
            "local stockKey = KEYS[1]                                   " +
                    "local boughtKey = KEYS[2]                                 " +
                    "local userId = ARGV[1]                                    " +
                    "                                                          " +
                    "if redis.call('sismember', boughtKey, userId) == 0 then   " +
                    "  return 0                                                " +
                    "end                                                       " +
                    "                                                          " +
                    "redis.call('incrby', stockKey, 1)                         " +
                    "redis.call('srem', boughtKey, userId)                     " +
                    "return 1                                                  ";

    private final RedisTemplate<String, Object> redisTemplate;

    private final DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> restoreScript = new DefaultRedisScript<>();

    public SeckillLuaScripts(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.seckillScript.setResultType(Long.class);
        this.seckillScript.setScriptText(SECKILL_SCRIPT);
        this.restoreScript.setResultType(Long.class);
        this.restoreScript.setScriptText(RESTORE_SCRIPT);
    }

    public long executeSeckill(String stockKey, String boughtKey, String userId) {
        return execute(seckillScript, stockKey, boughtKey, userId);
    }

    public long executeRestore(String stockKey, String boughtKey, String userId) {
        return execute(restoreScript, stockKey, boughtKey, userId);
    }

    private long execute(DefaultRedisScript<Long> script, String stockKey, String boughtKey, String userId) {
        List<String> keys = List.of(stockKey, boughtKey);
        Long result = redisTemplate.execute(script, keys, userId);
        return result == null ? -1 : result;
    }

    /** 供单元测试直接拿脚本原文校验 */
    public static List<String> keyOrder() {
        return List.of("stockKey", "boughtKey");
    }

    /** 单测辅助：本地模拟脚本逻辑（不连 Redis 也能验证分支） */
    public static long simulate(int stock, boolean alreadyBought) {
        if (stock <= 0) {
            return 0;
        }
        if (alreadyBought) {
            return -2;
        }
        return 1;
    }

    /** 预热库存 */
    public void warmUpStock(String stockKey, int stock) {
        redisTemplate.opsForValue().set(stockKey, stock);
    }

    /** 查剩余库存 */
    public Integer remainingStock(String stockKey) {
        Object v = redisTemplate.opsForValue().get(stockKey);
        return v == null ? null : Integer.parseInt(String.valueOf(v));
    }

    /** 清空活动数据（测试用） */
    public void reset(String stockKey, String boughtKey) {
        redisTemplate.delete(Collections.singletonList(stockKey));
        redisTemplate.delete(Collections.singletonList(boughtKey));
    }
}
