package com.novamall.gateway.filter;

import com.novamall.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关全局鉴权过滤器。
 *
 * <p>职责：校验 JWT → 解析出用户信息 → 以内部请求头的形式透传给下游服务。
 * 下游服务不再重复验签，直接读 Header 即可拿到 userId。</p>
 *
 * <p><b>为什么要放在网关统一鉴权？</b>
 * 如果每个微服务各自校验，一旦签名算法变更就要改 N 个服务；
 * 而且每个服务都要引入 JWT 依赖和配置密钥，密钥分散增加泄露风险。
 * 但要注意：网关只挡"外部流量"，服务间内网调用绕过了网关，
 * 所以生产环境还需要配合 mTLS 或服务网格做零信任。</p>
 *
 * <p><b>面试追问：用户信息通过 Header 传给下游安全吗？</b>
 * 不安全——如果有人能直接访问内网服务就能伪造 Header。
 * 生产做法：(1) 服务部署在内网/VPC，外部不可达；(2) Header 里传的是 JWT 而非明文 userId，
 * 下游再验一次签；(3) 加内网签名 Header（HMAC）。本项目做了简化，README 里说明了这个权衡。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    /** 用于校验"已退出登录"的 token 黑名单 */
    private final ReactiveStringRedisTemplate reactiveRedisTemplate;

    /** 不需要登录即可访问的路径 */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/product/**",
            "/api/seckill/list",
            "/actuator/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/webjars/**"
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 透传给下游的用户信息头 */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_ROLE = "X-Role-Code";

    /** 与 AuthService 中保持一致：退出登录的黑名单 key 前缀 */
    private static final String TOKEN_BLACKLIST_KEY = "auth:token:deny:";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isWhiteList(path)) {
            // 白名单也要剥离客户端伪造的用户头，防止越权
            ServerHttpRequest cleaned = stripUserHeaders(request);
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(exchange, "缺少登录凭证");
        }

        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            log.debug("[Gateway] JWT 校验失败 path={} msg={}", path, e.getMessage());
            return unauthorized(exchange, "登录已过期，请重新登录");
        }

        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String roleCode = claims.get("roleCode", String.class);

        ServerHttpRequest mutated = request.mutate()
                .header(HEADER_USER_ID, userId)
                .header(HEADER_USERNAME, username == null ? "" : username)
                .header(HEADER_ROLE, roleCode == null ? "USER" : roleCode)
                .build();

        // JWT 是无状态的，退出登录靠黑名单实现：查一次 Redis 判断 jti 是否已被作废
        String jti = claims.getId();
        if (jti == null) {
            return chain.filter(exchange.mutate().request(mutated).build());
        }
        return reactiveRedisTemplate.hasKey(TOKEN_BLACKLIST_KEY + jti)
                .flatMap(denied -> Boolean.TRUE.equals(denied)
                        ? unauthorized(exchange, "登录已失效，请重新登录")
                        : chain.filter(exchange.mutate().request(mutated).build()))
                // Redis 抖动时不能把正常用户拦在门外，降级为放行
                .onErrorResume(e -> chain.filter(exchange.mutate().request(mutated).build()));
    }

    private String resolveToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isWhiteList(String path) {
        return WHITE_LIST.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private ServerHttpRequest stripUserHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_ROLE);
                })
                .build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"code":4001,"message":"%s","data":null,"timestamp":%d}
                """.formatted(message, System.currentTimeMillis());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 数字越小优先级越高。鉴权要早于路由转发，但要晚于限流 */
    @Override
    public int getOrder() {
        return -100;
    }
}
