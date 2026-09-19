package com.novamall.job.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 分布式调度锁（保证多实例部署时定时任务只跑一次）。
 *
 * <h3>为什么不用 @Scheduled 直接跑？</h3>
 * <p>订单服务部署了 3 个实例，@Scheduled 是在每个 JVM 里独立触发的，
 * 没有任何协调机制 → 同一批数据被处理 3 次。
 * 对于"超时关单"这种操作，重复执行可能把状态改错；
 * 对于"发短信"这种，就是实打实的用户投诉。</p>
 *
 * <h3>三种解法</h3>
 * <ol>
 *   <li><b>Redis 分布式锁</b>（本项目）：轻量、够用。
 *       注意锁的 TTL 一定要大于任务执行时间，否则任务还没跑完锁就过期，别人又进来了。</li>
 *   <li><b>XXL-Job / ElasticJob</b>（推荐的生产方案）：
 *       有独立的调度中心和可视化控制台，支持分片广播、失败重试、报警、查看执行日志。
 *       而且"分片"功能可以把一个大任务拆成 N 份给 N 台机器并行跑，这是单机锁做不到的。</li>
 *   <li><b>Quartz 集群模式</b>：依赖数据库锁表实现，比较重，现在新项目用得少了。</li>
 * </ol>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLock {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 带锁执行任务。
     *
     * @param lockName 锁名
     * @param ttl      锁持有时间，必须大于任务执行耗时
     * @param task     任务逻辑
     */
    public void runOnce(String lockName, Duration ttl, Runnable task) {
        String key = "lock:task:" + lockName;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("[TaskLock] 任务 {} 已被其他实例执行，跳过", lockName);
            return;
        }
        try {
            task.run();
        } finally {
            // 只有自己还持有锁才删除（防止误删超时后被别人抢到的锁）
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                redisTemplate.delete(key);
            }
        }
    }

    public <T> T supplyOnce(String lockName, Duration ttl, Supplier<T> supplier) {
        String key = "lock:task:" + lockName;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }
        try {
            return supplier.get();
        } finally {
            redisTemplate.delete(key);
        }
    }
}
