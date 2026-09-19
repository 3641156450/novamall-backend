package com.novamall.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 注解式接口限流（令牌桶算法，基于 Redis + Lua 实现，全集群共享配额）。
 *
 * <p>为什么不用 Guava RateLimiter？它是单机 JVM 内的限流，
 * 服务部署 4 个实例时，实际放行量是配置值的 4 倍。
 * 要做集群限流必须把计数放到 Redis 这样的共享存储里。</p>
 *
 * <p>令牌桶 vs 漏桶 vs 计数器（面试必考）：
 * <ul>
 *   <li><b>固定窗口计数器</b>：实现最简单，但有临界突刺问题——
 *       00:59 打了 100 次，01:00 又打 100 次，两个窗口各自没超限，
 *       但在 2 秒内实际打了 200 次。</li>
 *   <li><b>滑动窗口</b>：把窗口切成小格，精度越高越准，但 Redis 里要存更多 key，内存占用大。</li>
 *   <li><b>漏桶</b>：以恒定速率出水，能绝对削峰但无法应对突发流量。</li>
 *   <li><b>令牌桶</b>：以恒定速率往桶里放令牌，请求来了取令牌，取不到就拒绝。
 *       桶容量允许一定程度的突发（比如攒了 10 个令牌，可以瞬间处理 10 个请求），
 *       兼顾了"限流"和"体验"，是工业界最常用方案（Guava RateLimiter、Sentinel 默认都是它）。</li>
 * </ul>
 *
 * @author NovaMall
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 key，支持 SpEL，如 "#userId"、"#request.getRemoteAddr()" */
    String key() default "";

    /** 资源名，用于区分不同接口。默认取"类名.方法名" */
    String resource() default "";

    /** 每秒生成多少个令牌（长期平均速率） */
    double permitsPerSecond() default 10;

    /** 桶容量（允许的瞬时突发量）。默认等于 permitsPerSecond */
    double capacity() default -1;

    /** 本次请求需要的令牌数 */
    int tokens() default 1;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /** 限流维度：按IP / 按用户 / 全局 */
    LimitScope scope() default LimitScope.GLOBAL;

    String message() default "操作过于频繁，请稍后再试";
}
