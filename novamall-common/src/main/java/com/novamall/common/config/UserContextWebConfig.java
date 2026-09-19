package com.novamall.common.config;

import com.novamall.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 从请求头还原用户上下文。
 *
 * <p>网关鉴权通过后会把 userId / username / roleCode 放进内部请求头，
 * 各微服务在这里统一解析并塞进 ThreadLocal，业务代码里直接 UserContext.getUserId() 即可。</p>
 *
 * <p><b>为什么必须在 afterCompletion 里 clear()？</b>
 * Tomcat 的线程池是复用的，线程 A 处理完请求 1 后不清理 ThreadLocal，
 * 下次用同一线程处理请求 2 时就会读到请求 1 的用户——这就是所谓的"用户信息串号"事故。
 * 这类 bug 只在并发下偶发，本地怎么测都是对的，线上却隔几天冒出来一次，非常难查。</p>
 *
 * @author NovaMall
 */
@Configuration
public class UserContextWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request,
                                             HttpServletResponse response,
                                             Object handler) {
                        String userId = request.getHeader("X-User-Id");
                        if (userId != null && !userId.isBlank()) {
                            try {
                                UserContext.set(new UserContext.UserInfo(
                                        Long.valueOf(userId),
                                        request.getHeader("X-Username"),
                                        request.getHeader("X-Role-Code")
                                ));
                            } catch (NumberFormatException e) {
                                // 非法 header，忽略即可，后面的鉴权注解会拦截
                            }
                        }
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request,
                                                HttpServletResponse response,
                                                Object handler,
                                                Exception ex) {
                        UserContext.clear();
                    }
                })
                .addPathPatterns("/**");
    }
}
