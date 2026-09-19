package com.novamall.common.aspect;

import com.novamall.common.annotation.DistributedLock;
import com.novamall.common.exception.BizException;
import com.novamall.common.lock.LockType;
import com.novamall.common.lock.RedisLockHelper;
import com.novamall.common.result.ResultCode;
import com.novamall.common.util.SpelHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 分布式锁切面：把"加锁-执行业务-释放锁"的模板代码从业务方法里抽出去。
 *
 * <p>没有这个切面时，每个方法都要写：
 * <pre>{@code
 * String lockKey = "lock:order:" + userId;
 * String value = UUID.randomUUID().toString();
 * try {
 *     if (!redis.setIfAbsent(...)) throw new BizException("请勿重复提交");
 *     return doBiz();
 * } finally {
 *     redis.execute(UNLOCK_SCRIPT, ...);   // 忘了写 finally 就是线上事故
 * }
 * }</pre>
 * 复制粘贴 20 处，总有一处忘了 finally，或者 leaseTime 写太短。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedisLockHelper redisLockHelper;
    private final SpelHelper spelHelper;

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        String rawKey = spelHelper.parse(distributedLock.key(), method, joinPoint.getArgs());
        String lockKey = distributedLock.prefix() + ":" + rawKey;

        long leaseMillis = distributedLock.timeUnit().toMillis(distributedLock.leaseTime());
        long waitMillis = distributedLock.timeUnit().toMillis(distributedLock.waitTime());

        boolean acquired;
        String lockValue = null;
        String reentrantField = null;

        if (distributedLock.type() == LockType.REENTRANT) {
            reentrantField = Thread.currentThread().getId() + ":" + redisLockHelper.buildLockValue();
            acquired = redisLockHelper.tryReentrantLock(lockKey, reentrantField, waitMillis, leaseMillis);
        } else {
            lockValue = redisLockHelper.tryLock(lockKey, waitMillis, leaseMillis);
            acquired = lockValue != null;
        }

        if (!acquired) {
            log.warn("[DistributedLock] 获取锁失败 key={} wait={}ms", lockKey, waitMillis);
            if (distributedLock.throwIfFail()) {
                throw new BizException(ResultCode.REQUEST_TOO_FREQUENT);
            }
            return null;
        }

        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            // 无论业务成功/异常/超时，都必须释放锁，否则会造成长时间阻塞
            if (distributedLock.type() == LockType.REENTRANT) {
                redisLockHelper.unlockReentrant(lockKey, reentrantField);
            } else {
                redisLockHelper.unlock(lockKey, lockValue);
            }
            long cost = System.currentTimeMillis() - start;
            if (cost > leaseMillis * 0.8) {
                // 业务执行时间接近锁的超时时间，说明 leaseTime 设小了，需要告警
                log.warn("[DistributedLock] 业务执行 {}ms，已接近锁超时 {}ms，key={}", cost, leaseMillis, lockKey);
            }
        }
    }
}
