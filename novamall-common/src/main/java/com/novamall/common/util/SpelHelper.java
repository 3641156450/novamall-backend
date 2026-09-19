package com.novamall.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL 表达式解析：让注解里的 key 可以直接引用方法参数。
 *
 * <p>例如 {@code @DistributedLock(key = "#orderDTO.userId")} 能拿到入参对象的字段。</p>
 *
 * <p>注意：这里缓存的是 Expression 对象，不是解析结果。
 * SpEL 表达式编译有开销，但同一个注解位置的表达式是固定的，所以缓存后只解析一次。</p>
 *
 * @author NovaMall
 */
@Component
@RequiredArgsConstructor
public class SpelHelper {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /** key: 方法签名+表达式, value: 已解析的 Expression */
    private final ConcurrentHashMap<String, org.springframework.expression.Expression> cache = new ConcurrentHashMap<>();

    public String parse(String expression, Method method, Object[] args) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        // 纯字符串常量（不带 #）直接返回，省去解析开销
        if (!expression.contains("#")) {
            return expression;
        }

        String cacheKey = method.toGenericString() + "|" + expression;
        org.springframework.expression.Expression exp = cache.computeIfAbsent(cacheKey, PARSER::parseExpression);

        EvaluationContext context = new MethodBasedEvaluationContext(null, method, args, NAME_DISCOVERER);
        Object value = exp.getValue(context);
        return value == null ? "null" : String.valueOf(value);
    }
}
