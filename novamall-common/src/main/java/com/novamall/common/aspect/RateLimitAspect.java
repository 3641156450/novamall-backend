package com.novamall.common.aspect;

import com.novamall.common.annotation.LimitScope;
import com.novamall.common.annotation.RateLimit;
import com.novamall.common.context.UserContext;
import com.novamall.common.exception.BizException;
import com.novamall.common.result.ResultCode;
import com.novamall.common.util.SpelHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * 限流切面：Redis + Lua 实现集群级令牌桶。
 *
 * <p>Lua 脚本逻辑（在 Redis 里原子执行）：
 * <pre>
 * 1. 读 hash：{tokens: 当前令牌数, ts: 上次补充时间}
 * 2. 按 (now - ts) * rate 计算这段时间新生成的令牌，累加到 tokens（不超过 capacity）
 * 3. 如果 tokens >= requested，扣减并返回 1（放行）；否则返回 0（拒绝）
 * </pre>
 * 整个计算在 Redis 单线程里完成，天然并发安全，不需要在 Java 里加锁。</p>
 *
 * <p>为什么不用 Redisson 的 RRateLimiter？
 * 用是能用，但面试时讲不出原理。自己写一遍可以精准回答
 * "令牌是怎么补充的""桶满了一直放令牌会不会溢出"这类问题。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SpelHelper spelHelper;

    private static final String KEY_PREFIX = "ratelimit:";

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();

    static {
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
        TOKEN_BUCKET_SCRIPT.setScriptText(
                "local key = KEYS[1]                                  " +
                        "local rate = tonumber(ARGV[1])                       " +  // 每秒生成令牌数
                        "local capacity = tonumber(ARGV[2])                   " +  // 桶容量
                        "local now = tonumber(ARGV[3])                        " +  // 当前毫秒时间戳
                        "local requested = tonumber(ARGV[4])                  " +  // 本次需要的令牌数
                        "local bucket = redis.call('hgetall', key)            " +
                        "local tokens = capacity                              " +
                        "local ts = now                                       " +
                        "if #bucket > 0 then                                  " +
                        "  tokens = tonumber(bucket[2])                       " +
                        "  ts = tonumber(bucket[4])                           " +
                        "end                                                  " +
                        "local delta = math.max(0, now - ts) / 1000.0         " +
                        "tokens = math.min(capacity, tokens + delta * rate)   " +
                        "local allowed = 0                                    " +
                        "if tokens >= requested then                          " +
                        "  tokens = tokens - requested                        " +
                        "  allowed = 1                                        " +
                        "end                                                  " +
                        "redis.call('hset', key, 'tokens', tokens, 'ts', now) " +
                        "redis.call('pexpire', key, 3600000)                  " +  // 1 小时没访问就清理，避免内存泄漏
                        "return allowed                                       "
        );
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        String resource = rateLimit.resource().isBlank()
                ? method.getDeclaringClass().getSimpleName() + "." + method.getName()
                : rateLimit.resource();

        String dimension = resolveDimension(rateLimit, method, joinPoint.getArgs());
        String redisKey = KEY_PREFIX + resource + ":" + dimension;

        double rate = rateLimit.permitsPerSecond();
        if (rateLimit.timeUnit() == java.util.concurrent.TimeUnit.MINUTES) {
            rate = rate / 60.0;
        }
        double capacity = rateLimit.capacity() < 0 ? rate : rateLimit.capacity();

        List<String> keys = Collections.singletonList(redisKey);
        Object[] argv = {
                String.valueOf(rate),
                String.valueOf(capacity),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(rateLimit.tokens())
        };

        Long allowed;
        try {
            allowed = redisTemplate.execute(TOKEN_BUCKET_SCRIPT, keys, argv);
        } catch (Exception e) {
            // 限流组件本身出问题时，选择"放行"而不是"全站拒绝"——熔断降级的核心思想
            log.error("[RateLimit] 限流脚本执行异常，本次放行 redisKey={}", redisKey, e);
            return joinPoint.proceed();
        }

        if (allowed == null || allowed == 0) {
            log.warn("[RateLimit] 触发限流 resource={} dimension={}", resource, dimension);
            throw new BizException(ResultCode.REQUEST_TOO_FREQUENT.getCode(), rateLimit.message());
        }

        return joinPoint.proceed();
    }

    private String resolveDimension(RateLimit rateLimit, Method method, Object[] args) {
        return switch (rateLimit.scope()) {
            case GLOBAL -> "global";
            case IP -> clientIp();
            case USER -> {
                Long userId = UserContext.getUserId();
                yield userId == null ? "anonymous:" + clientIp() : "user:" + userId;
            }
            case CUSTOM -> {
                String parsed = spelHelper.parse(rateLimit.key(), method, args);
                yield parsed.isBlank() ? "global" : parsed;
            }
        };
    }

    private String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        // 经过 Nginx 后真实 IP 在 X-Forwarded-For 第一段
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (Exception ignored) {
            // 非 Web 环境
        }
        return null;
    }
}
