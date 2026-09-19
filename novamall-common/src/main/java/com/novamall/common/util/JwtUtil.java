package com.novamall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类（jjwt 0.12.x API）。
 *
 * <p>面试高频追问：
 * <ol>
 *   <li><b>JWT 存在哪？Cookie 还是 LocalStorage？</b>
 *       放 localStorage 有 XSS 风险；放 Cookie 有 CSRF 风险。本项目选择放 Header，
 *       由前端自己存（存内存/ sessionStorage），并且 Cookie 走 HttpOnly 时会开 SameSite=Lax 防 CSRF。</li>
 *   <li><b>JWT 怎么注销？</b> JWT 本身无状态，签发后不可撤销。常见三种做法：
 *       (a) 短有效期 + Refresh Token；(b) 服务端维护黑名单（Redis 存已退出的 jti，过期时间=剩余有效期）；
 *       (c) 改 token 版本号，用户表加 token_version 字段，退出时 +1，校验时比对。
 *       本项目用了 (b)，见 AuthService#logout。</li>
 *   <li><b>签名密钥怎么管？</b> 生产环境不能硬编码在代码里，走配置中心（Nacos）或环境变量注入。</li>
 * </ol>
 *
 * @author NovaMall
 */
@Slf4j
public class JwtUtil {

    /** HS256 要求密钥长度 >= 32 字节 */
    private final SecretKey secretKey;
    private final long expireMillis;

    public JwtUtil(String secret, long expireMillis) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT 密钥长度至少需要 32 字节，当前：" + keyBytes.length);
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = expireMillis;
    }

    public String generateToken(Long userId, String username, String roleCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roleCode", roleCode)
                // jti：唯一 ID，用于做退出登录黑名单
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(secretKey)
                .compact();
    }

    /** 解析失败（过期/签名错误/格式错误）统一抛 JwtException，由调用方转业务异常 */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getJti(String token) {
        return parse(token).getId();
    }

    /** 剩余有效期（毫秒），用于"退出登录时按剩余时间设置黑名单 TTL" */
    public long remainingMillis(String token) {
        Date expiration = parse(token).getExpiration();
        long remain = expiration.getTime() - System.currentTimeMillis();
        return Math.max(remain, 0);
    }

    public long getExpireMillis() {
        return expireMillis;
    }

    /** 校验但不抛异常，返回 null 表示无效 */
    public Claims tryParse(String token) {
        try {
            return parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> toUserInfo(String token) {
        Claims claims = parse(token);
        return Map.of(
                "userId", Long.valueOf(claims.getSubject()),
                "username", claims.get("username", String.class),
                "roleCode", claims.get("roleCode", String.class)
        );
    }
}
