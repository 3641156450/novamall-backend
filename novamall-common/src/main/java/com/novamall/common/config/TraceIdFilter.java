package com.novamall.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪 ID 过滤器。
 *
 * <p>解决的问题：线上报错时，一个请求会经过 gateway → order → product → MQ，
 * 每个服务各打各的日志，靠时间戳去对几乎不可能。
 * 统一生成 traceId 放进 SLF4J 的 MDC，日志格式里加 %X{traceId}，
 * 就能用一个 grep 命令捞出一整条链路的所有日志。</p>
 *
 * <p>如果上游（网关）已经带过来 X-Trace-Id，就沿用；否则自己生成一个。
 * 这是 OpenTelemetry / SkyWalking 等链路追踪系统的基本思想。</p>
 *
 * <p>注意：MDC 也是 ThreadLocal，异步线程里拿不到，需要额外做传递；
 * 请求结束时必须 clear，否则线程池复用会串 ID。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (!StringUtils.hasText(traceId)) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            MDC.put(MDC_KEY, traceId);
            // 回写给响应头，方便前端/测试同学提供 traceId 让我们排查
            response.setHeader(TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
