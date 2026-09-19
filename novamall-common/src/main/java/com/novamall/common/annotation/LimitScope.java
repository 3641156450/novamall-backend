package com.novamall.common.annotation;

/**
 * 限流维度。
 *
 * @author NovaMall
 */
public enum LimitScope {

    /** 全局：整个接口所有请求共享配额，保护下游服务 */
    GLOBAL,

    /** 按 IP：防爬虫、防单 IP 刷接口 */
    IP,

    /** 按用户：防某个用户恶意刷单，比 IP 更精准（IP 可能是公司出口 IP） */
    USER,

    /** 自定义：用 {@link RateLimit#key()} 里的 SpEL 表达式决定 */
    CUSTOM
}
