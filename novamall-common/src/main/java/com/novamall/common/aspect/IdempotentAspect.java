package com.novamall.common.aspect;

import com.novamall.common.annotation.Idempotent;
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

/**
 * 幂等切面。
 *
 * <p>核心是这个 Lua 脚本：
 * <pre>{@code
 * if redis.call('get', KEYS[1]) == ARGV[1] then
 *     return redis.call('del', KEYS[1])
 * else
 *     return 0
 * end
 * }</pre>
 * 和分布式锁的释放是同一个套路——"判断+删除"必须原子，
 * 否则两个并发请求可能同时通过 GET 判断，然后都去执行业务。</p>
 *
 * <p>面试追问：<b>如果业务执行失败了，token 已经被删了怎么办？</b>
 * 答：失败后由调用方重新申请 token 再提交（本项目就是这样设计的）。
 * 另一种做法是业务失败时把 token 写回去，但那会引入"失败重试风暴"，需要谨慎。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SpelHelper spelHelper;

    private static final String KEY_PREFIX = "idempotent:";

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>();

    static {
        CONSUME_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('del', KEYS[1]) " +
                        "else " +
                        "  return 0 " +
                        "end"
        );
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        HttpServletRequest request = currentRequest();
        String token;

        if (!idempotent.param().isBlank()) {
            token = spelHelper.parse(idempotent.param(), method, joinPoint.getArgs());
        } else {
            Object[] args = joinPoint.getArgs();
            if (request != null) {
                // 把 request 追加进参数列表，便于 SpEL 写 "#request.getHeader(...)"
                Object[] withRequest = new Object[args.length + 1];
                System.arraycopy(args, 0, withRequest, 0, args.length);
                withRequest[args.length] = request;
                token = spelHelper.parse(idempotent.key(), method, withRequest);
            } else {
                token = spelHelper.parse(idempotent.key(), method, args);
            }
        }

        if (token == null || token.isBlank() || "null".equals(token)) {
            if (idempotent.requireKey()) {
                throw new BizException(ResultCode.PARAM_ERROR.getCode(), "缺少幂等令牌，请先获取");
            }
            return joinPoint.proceed();
        }

        String redisKey = KEY_PREFIX + token;
        Long deleted = redisTemplate.execute(CONSUME_SCRIPT, Collections.singletonList(redisKey), token);

        if (deleted == null || deleted == 0) {
            log.warn("[Idempotent] 重复请求被拦截 token={}", token);
            throw new BizException(ResultCode.REPEAT_SUBMIT.getCode(), idempotent.message());
        }

        // 注意：这里不设置 TTL。token 被消费即删除；
        // 若用户申请后一直不提交，则由申请时设置的 TTL 自动过期（见 IdempotentService）
        return joinPoint.proceed();
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (Exception ignored) {
            // 非 Web 环境（如 MQ 消费者、定时任务）没有 RequestContext，返回 null 即可
        }
        return null;
    }
}
