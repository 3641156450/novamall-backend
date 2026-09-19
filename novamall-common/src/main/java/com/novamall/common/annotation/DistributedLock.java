package com.novamall.common.annotation;

import com.novamall.common.lock.LockType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 注解式分布式锁。用法：
 * <pre>{@code
 * @DistributedLock(key = "#orderDTO.userId + ':' + #orderDTO.productId",
 *                  leaseTime = 10, waitTime = 3, type = LockType.REENTRANT)
 * public Long createOrder(OrderDTO orderDTO) { ... }
 * }</pre>
 *
 * <p>key 支持 SpEL，可以引用方法参数（#参数名）和方法所在 Bean（#this）。</p>
 *
 * @author NovaMall
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** 锁的 key 前缀，最终 key = prefix + ":" + 解析后的 key */
    String prefix() default "lock";

    /** 锁的 key，支持 SpEL 表达式 */
    String key();

    /** 持锁最长时长（秒）。必须大于业务执行时间，否则业务没跑完锁就被别人抢走了 */
    long leaseTime() default 10;

    /** 获取锁的最长等待时间（秒）。0 表示不等待，拿不到立即失败 */
    long waitTime() default 3;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    LockType type() default LockType.SIMPLE;

    /** 拿不到锁时是否抛异常；false 则直接返回 null（慎用，容易掩盖问题） */
    boolean throwIfFail() default true;
}
