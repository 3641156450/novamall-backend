package com.novamall.order.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 请求头透传。
 *
 * <p>问题：用户请求 A 服务，A 用 Feign 调 B。
 * Feign 发起的是一个全新的 HTTP 请求，原请求里的 Header 一个都不会带过去。
 * 结果 B 服务拿不到 X-User-Id，UserContext 是空的，鉴权注解直接把请求拒了。</p>
 *
 * <p>解法：实现 RequestInterceptor，在模板里把需要的 Header 补上。
 * 除了用户信息，traceId 也必须透传，否则链路就断了。</p>
 *
 * @author NovaMall
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor feignRequestInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                var attrs = RequestContextHolder.getRequestAttributes();
                if (attrs instanceof ServletRequestAttributes sra) {
                    copyHeader(template, sra, "X-User-Id");
                    copyHeader(template, sra, "X-Username");
                    copyHeader(template, sra, "X-Role-Code");
                }
                // traceId 从 MDC 里取，保证跨服务的日志能串起来
                String traceId = MDC.get("traceId");
                if (traceId != null) {
                    template.header("X-Trace-Id", traceId);
                }
            }

            private void copyHeader(RequestTemplate template, ServletRequestAttributes sra, String name) {
                String value = sra.getRequest().getHeader(name);
                if (value != null && !value.isBlank()) {
                    template.header(name, value);
                }
            }
        };
    }
}
