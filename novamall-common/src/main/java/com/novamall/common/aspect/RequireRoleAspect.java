package com.novamall.common.aspect;

import com.novamall.common.annotation.RequireRole;
import com.novamall.common.context.UserContext;
import com.novamall.common.exception.BizException;
import com.novamall.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * 角色校验切面。
 *
 * <p>为什么用 AOP 而不是拦截器？
 * 拦截器只能拦到 URL，粒度太粗；AOP 能精确到方法，
 * 配合"内部调用不走网关"的场景（比如定时任务里调 AdminService）也能生效。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(requireRole)")
    public Object around(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        UserContext.UserInfo userInfo = UserContext.get();
        if (userInfo == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }

        String actual = userInfo.roleCode();
        Set<String> required = Set.copyOf(Arrays.asList(requireRole.value()));

        boolean passed = switch (requireRole.mode()) {
            case ANY -> required.contains(actual);
            case ALL -> required.size() == 1 && required.contains(actual) || actual != null && required.stream().allMatch(actual::contains);
        };

        if (!passed) {
            log.warn("[RequireRole] 越权拦截 user={} role={} required={}", userInfo.userId(), actual, required);
            throw new BizException(ResultCode.FORBIDDEN);
        }

        return joinPoint.proceed();
    }
}
