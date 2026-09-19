package com.novamall.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等注解。
 *
 * <p>什么是幂等？同一个请求执行一次和执行一百次，对系统状态的影响完全一致。</p>
 *
 * <p>常见产生重复请求的原因：
 * <ul>
 *   <li>用户手抖连点两次提交按钮</li>
 *   <li>前端超时重试 / 网关重试</li>
 *   <li>MQ 消息重复投递（网络抖动导致 ACK 没送达）</li>
 *   <li>支付回调被第三方重复通知</li>
 * </ul>
 *
 * <p>本项目采用的方案：<b>Token 机制 + Redis 原子删除</b>。
 * 下单前先调 /api/order/token 拿一个一次性令牌（存 Redis），
 * 下单时带上，服务端用 Lua 脚本"存在才删除"，删除成功才放行。
 * 这是原子操作，比"先 GET 判断再 DEL"安全。</p>
 *
 * <p>其他方案对比（面试常问）：
 * <ul>
 *   <li>数据库唯一索引：最可靠，兜底必做。本项目 order 表的 order_no 建了唯一索引。</li>
 *   <li>乐观锁（version 字段）：适合更新场景，如扣库存 update ... where stock>0 and version=?。</li>
 *   <li>状态机：订单状态流转只允许单向，如"已支付"不能回到"待支付"。</li>
 *   <li>分布式锁：能防并发但防不住"间隔很久的重复提交"，且会串行化降低吞吐。</li>
 * </ul>
 * </p>
 *
 * @author NovaMall
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等 key 的 SpEL 表达式，默认取请求头里的 token */
    String key() default "#request.getHeader('Idempotent-Token')";

    /** 也可以直接用参数名，如 "#orderDTO.token" */
    String param() default "";

    long expireSeconds() default 60;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    String message() default "请勿重复提交";

    /** key 为空时的行为：true=抛异常拒绝，false=放行（用于只对部分场景做幂等） */
    boolean requireKey() default true;
}
