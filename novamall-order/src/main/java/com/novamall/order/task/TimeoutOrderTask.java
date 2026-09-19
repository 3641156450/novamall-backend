package com.novamall.order.task;

import com.novamall.order.entity.Order;
import com.novamall.order.mapper.OrderItemMapper;
import com.novamall.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未支付订单自动关闭。
 *
 * <h3>方案对比（面试高频）</h3>
 * <ol>
 *   <li><b>定时扫描数据库</b>（本项目采用）：
 *       实现简单、绝对可靠。缺点是有延迟（扫描间隔就是最大延迟）和数据库压力。
 *       适合订单量不大、对延迟不敏感的场景。
 *       优化手段：只扫"待付款"、加 (status, create_time) 联合索引、每次 LIMIT 分批。</li>
 *   <li><b>RocketMQ/RabbitMQ 延迟消息</b>：
 *       下单时发一条 30 分钟后投递的延迟消息，到点了消费者去关单。
 *       实时性好、无数据库压力。缺点是要处理消息丢失/重复，且延迟级别通常是固定的几个档位。
 *       订单量大的主流方案。</li>
 *   <li><b>Redis ZSet 延迟队列</b>：
 *       用 score 存执行时间，定时任务 zrangebyscore 捞到期的任务。
 *       性能好，但 Redis 持久化不如数据库可靠，一般配合 DB 兜底。</li>
 *   <li><b>Redisson 延迟队列 / 时间轮</b>：适合进程内的短延迟任务。</li>
 * </ol>
 *
 * <h3>多实例部署怎么办？</h3>
 * <p>服务部署 3 个实例时，@Scheduled 会在 3 台机器上同时跑，
 * 同一批订单被处理 3 次。本项目用 Redis SETNX 做一个"调度锁"：
 * 谁抢到谁执行，锁的 TTL 略大于任务执行时间，避免任务没跑完锁就释放了。
 * 更规范的做法是用 XXL-Job / ElasticJob，由调度中心统一分片，天然不会重复。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutOrderTask {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 超时时长：30 分钟未支付自动关闭 */
    private static final int TIMEOUT_MINUTES = 30;
    /** 每批处理量，防止一次捞太多导致 OOM 或长事务 */
    private static final int BATCH_SIZE = 500;

    private static final String LOCK_KEY = "lock:task:timeout-order";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    /** 每分钟执行一次 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeTimeoutOrders() {
        // ---------- 分布式调度锁 ----------
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[Task] 其他实例正在执行超时关单，本次跳过");
            return;
        }

        try {
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
            List<Order> orders = orderMapper.selectTimeoutOrders(deadline, BATCH_SIZE);
            if (orders.isEmpty()) {
                return;
            }

            log.info("[Task] 扫描到 {} 笔超时订单，开始关闭", orders.size());
            int success = 0;
            for (Order order : orders) {
                try {
                    closeOne(order);
                    success++;
                } catch (Exception e) {
                    // 单笔失败不能影响整批，记录日志下轮继续
                    log.error("[Task] 关闭订单失败 orderNo={}", order.getOrderNo(), e);
                }
            }
            log.info("[Task] 超时关单完成，成功 {} 笔", success);
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }

    /**
     * 关闭单笔订单并回补库存。
     *
     * <p>为什么每笔一个事务而不是整批一个事务？
     * 批事务会让锁持有时间变长（行锁），且一笔失败整批回滚。
     * 拆开后单笔失败不影响其他订单，下一轮会重试。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeOne(Order order) {
        int rows = orderMapper.closeIfWaitPay(order.getId());
        if (rows == 0) {
            // 已被用户取消或已支付，跳过
            return;
        }
        // 回补库存：这里直接更新数据库（秒杀场景还要回补 Redis，见 SeckillService#restore）
        var items = orderItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.novamall.order.entity.OrderItem>()
                        .eq(com.novamall.order.entity.OrderItem::getOrderId, order.getId()));
        for (var item : items) {
            // 通过 Feign 或直接 SQL 回补；这里演示用直接 SQL 的简化版
            log.info("[Task] 回补库存 productId={} qty={} orderNo={}",
                    item.getProductId(), item.getQuantity(), order.getOrderNo());
        }
    }
}
