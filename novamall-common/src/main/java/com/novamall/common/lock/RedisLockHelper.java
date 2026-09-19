package com.novamall.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁底层实现（基于 Redis + Lua）。
 *
 * <p>为什么加锁/解锁必须用 Lua 脚本？
 * 因为"判断是不是自己的锁"和"删除锁"是两个操作，如果分开发两条命令：
 * <ol>
 *   <li>线程 A 判断 value 是自己的 → 准备删除</li>
 *   <li>此时 A 的锁刚好过期，线程 B 成功加锁</li>
 *   <li>A 继续执行删除 → 把 B 的锁删掉了</li>
 * </ol>
 * Redis 单线程执行 Lua 脚本能保证这两步的原子性。</p>
 *
 * <p>四个必要条件（面试必背）：
 * <ul>
 *   <li>加锁原子性：SET key value NX PX expire，一条命令搞定，不能先 SETNX 再 EXPIRE（中间宕机会死锁）</li>
 *   <li>解锁原子性：Lua 判断 value 再 DEL</li>
 *   <li>解锁正确性：value 必须是唯一随机值（UUID+线程ID），防止误删别人的锁</li>
 *   <li>过期兜底：即使服务宕机，锁也会因 TTL 自动释放，不会永久死锁</li>
 * </ul>
 * </p>
 *
 * <p><b>本实现的已知局限（面试主动说出来反而加分）：</b>
 * 单 Redis 实例下没问题；主从切换时如果锁还没同步到从节点就主节点宕机，会丢锁。
 * 生产级方案是 Redlock（多实例多数派）或直接用 Redisson 的 RedissonLock（内置看门狗自动续期）。
 * 本项目为了讲清原理手写了一遍，README 里也写了与 Redisson 的对比。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockHelper {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String LOCK_VALUE_PREFIX = UUID.randomUUID().toString().replace("-", "");

    /** 释放锁的 Lua：value 匹配才删除 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();

    /** 可重入加锁 Lua：存在且是自己的锁则重入次数 +1 并续期 */
    private static final DefaultRedisScript<Long> REENTRANT_LOCK_SCRIPT = new DefaultRedisScript<>();

    /** 可重入解锁 Lua：重入次数 -1，减到 0 才删除 */
    private static final DefaultRedisScript<Long> REENTRANT_UNLOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        UNLOCK_SCRIPT.setResultType(Long.class);
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('del', KEYS[1]) " +
                        "else " +
                        "  return 0 " +
                        "end"
        );

        REENTRANT_LOCK_SCRIPT.setResultType(Long.class);
        REENTRANT_LOCK_SCRIPT.setScriptText(
                "local key = KEYS[1] " +
                        "local field = ARGV[1] " +
                        "local ttl = tonumber(ARGV[2]) " +
                        "if redis.call('exists', key) == 0 then " +
                        "  redis.call('hset', key, field, 1) " +
                        "  redis.call('pexpire', key, ttl) " +
                        "  return 1 " +
                        "end " +
                        "if redis.call('hexists', key, field) == 1 then " +
                        "  redis.call('hincrby', key, field, 1) " +
                        "  redis.call('pexpire', key, ttl) " +
                        "  return 1 " +
                        "end " +
                        "return 0"
        );

        REENTRANT_UNLOCK_SCRIPT.setResultType(Long.class);
        REENTRANT_UNLOCK_SCRIPT.setScriptText(
                "local key = KEYS[1] " +
                        "local field = ARGV[1] " +
                        "if redis.call('hexists', key, field) == 0 then " +
                        "  return 0 " +
                        "end " +
                        "local counter = tonumber(redis.call('hincrby', key, field, -1)) " +
                        "if counter > 0 then " +
                        "  return 0 " +
                        "else " +
                        "  redis.call('del', key) " +
                        "  return 1 " +
                        "end"
        );
    }

    /** 生成"值唯一"的锁标识：进程随机前缀 + 线程 ID */
    public String buildLockValue() {
        return LOCK_VALUE_PREFIX + "-" + Thread.currentThread().getId() + "-" + System.nanoTime();
    }

    /**
     * 尝试加锁（不可重入版）。
     *
     * @return 锁 value，null 表示没抢到
     */
    public String tryLock(String lockKey, long waitTimeMillis, long leaseTimeMillis) {
        String value = buildLockValue();
        long deadline = System.currentTimeMillis() + waitTimeMillis;

        while (true) {
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, value, leaseTimeMillis, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(success)) {
                return value;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            // 自旋间隔：太短会打满 CPU，太长会拉长响应时间。50~100ms 是比较常见的折中
            try {
                Thread.sleep(Math.min(50, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 释放锁（不可重入版） */
    public void unlock(String lockKey, String lockValue) {
        try {
            Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
            if (result == null || result == 0) {
                log.warn("[RedisLock] 释放锁失败，可能已过期或被他人持有 key={}", lockKey);
            }
        } catch (Exception e) {
            log.error("[RedisLock] 释放锁异常 key={}", lockKey, e);
        }
    }

    /** 可重入加锁 */
    public boolean tryReentrantLock(String lockKey, String field, long waitTimeMillis, long leaseTimeMillis) {
        long deadline = System.currentTimeMillis() + waitTimeMillis;
        while (true) {
            Long result = redisTemplate.execute(
                    REENTRANT_LOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    field, String.valueOf(leaseTimeMillis));
            if (result != null && result == 1) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.min(50, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 可重入解锁 */
    public void unlockReentrant(String lockKey, String field) {
        try {
            redisTemplate.execute(REENTRANT_UNLOCK_SCRIPT, Collections.singletonList(lockKey), field);
        } catch (Exception e) {
            log.error("[RedisLock] 可重入锁释放异常 key={}", lockKey, e);
        }
    }

    /** 批量 key，供 Lua 使用（保持扩展性） */
    public List<String> keys(String... keys) {
        return List.of(keys);
    }
}
