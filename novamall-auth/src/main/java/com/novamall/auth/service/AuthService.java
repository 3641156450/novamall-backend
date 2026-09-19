package com.novamall.auth.service;

import com.novamall.auth.dto.LoginRequest;
import com.novamall.auth.dto.LoginResponse;
import com.novamall.auth.dto.RegisterRequest;
import com.novamall.auth.entity.User;
import com.novamall.auth.mapper.UserMapper;
import com.novamall.common.config.JwtProperties;
import com.novamall.common.exception.BizException;
import com.novamall.common.result.ResultCode;
import com.novamall.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务。
 *
 * <p>三个值得讲的设计点：</p>
 *
 * <h3>1. 密码为什么用 BCrypt 而不是 MD5/SHA？</h3>
 * <ul>
 *   <li>MD5/SHA 设计目标是"快"，一秒能算几百万次，彩虹表 + 撞库成本极低。</li>
 *   <li>BCrypt 内建随机盐（同一个密码两次哈希结果不同），且有意设计得很慢
 *       （有个 strength 参数，默认 10，可以随硬件升级调大）。</li>
 *   <li>更现代的选择是 Argon2（抗 ASIC/GPU 更强），Spring Security 也支持。</li>
 * </ul>
 *
 * <h3>2. JWT 无状态，怎么做"退出登录"？</h3>
 * <p>token 签发后服务端没有状态，无法直接作废。本项目做法：退出时把 token 的 jti
 * 放进 Redis 黑名单，TTL 设为 token 的剩余有效期（到期自动清理，不占内存）。
 * 网关校验时多查一次 Redis。代价是每次请求多一次 Redis 读，
 * 换来的是"退出立即生效"这个强需求。</p>
 *
 * <h3>3. 登录为什么要在 Redis 里计数？</h3>
 * <p>防暴力破解：同一用户名连续失败 5 次就锁定 10 分钟。
 * 只在单机内存里计数不行——攻击者可以打到集群里另一个实例继续试。</p>
 *
 * @author NovaMall
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    /** BCrypt 强度：10。数字每 +1，耗时翻倍 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    private static final String LOGIN_FAIL_KEY = "auth:login:fail:";
    private static final String TOKEN_BLACKLIST_KEY = "auth:token:deny:";
    private static final int MAX_FAIL_COUNT = 5;
    private static final long LOCK_SECONDS = 600;

    /** 登录 */
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        String failKey = LOGIN_FAIL_KEY + username;

        // 1) 先看有没有被锁
        Integer failCount = (Integer) redisTemplate.opsForValue().get(failKey);
        if (failCount != null && failCount >= MAX_FAIL_COUNT) {
            Long ttl = redisTemplate.getExpire(failKey, TimeUnit.SECONDS);
            throw new BizException(ResultCode.REQUEST_TOO_FREQUENT.getCode(),
                    "密码错误次数过多，账号已锁定，请 " + (ttl == null ? 10 : ttl) + " 秒后重试");
        }

        // 2) 查用户
        User user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1) {
                // 第一次失败时才设置过期时间，避免每次续期导致永远锁死
                redisTemplate.expire(failKey, LOCK_SECONDS, TimeUnit.SECONDS);
            }
            long remain = MAX_FAIL_COUNT - (count == null ? 1 : count);
            // 提示语不区分"用户不存在"和"密码错误"，防止账号枚举攻击
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(),
                    "用户名或密码错误，还可尝试 " + Math.max(remain, 0) + " 次");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系客服");
        }

        // 3) 登录成功，清掉失败计数
        redisTemplate.delete(failKey);

        // 4) 签发 token
        long expire = Boolean.TRUE.equals(request.getRememberMe())
                ? jwtProperties.getRememberMeMillis()
                : jwtProperties.getExpireMillis();

        // 为了支持 rememberMe 的不同有效期，这里临时用一个新的 JwtUtil 实例。
        // 真实项目里更优雅的做法是把 expire 作为 generateToken 的入参。
        String token = new JwtUtil(jwtProperties.getSecret(), expire)
                .generateToken(user.getId(), user.getUsername(), user.getRoleCode());

        return LoginResponse.builder()
                .token(token)
                .expireIn(expire / 1000)
                .userId(user.getId())
                .username(user.getUsername())
                .roleCode(user.getRoleCode())
                .build();
    }

    /** 注册 */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest request) {
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            throw new BizException(ResultCode.PARAM_ERROR.getCode(), "用户名已被注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        user.setRoleCode("USER");
        user.setStatus(1);
        userMapper.insert(user);

        // 注册后自动登录，省一次交互
        LoginRequest autoLogin = new LoginRequest();
        autoLogin.setUsername(request.getUsername());
        autoLogin.setPassword(request.getPassword());
        return login(autoLogin);
    }

    /**
     * 退出登录：把 token 加入黑名单。
     *
     * @param token 原始 token（不带 Bearer 前缀）
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            String jti = jwtUtil.getJti(token);
            long remain = jwtUtil.remainingMillis(token);
            if (remain > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_KEY + jti,
                        "1",
                        Duration.ofMillis(remain));
            }
        } catch (Exception e) {
            // token 本身已失效就无所谓了，直接忽略
            log.debug("[Auth] 退出登录处理异常，忽略：{}", e.getMessage());
        }
    }

    /** 判断 token 是否在黑名单里（供网关调用） */
    public boolean isTokenDenied(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String jti = jwtUtil.getJti(token);
            return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_KEY + jti));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 手机号脱敏存储演示。
     *
     * <p>《个人信息保护法》要求：展示和存储都要遵循最小必要原则。
     * 列表页返回手机号必须脱敏成 138****8888。
     * 另外注意：加盐哈希不等于加密，哈希不可逆，所以这里只是演示摘要用途。</p>
     */
    public String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 演示：对用户敏感信息做摘要（实际项目请用脱敏 + 加密存储） */
    public String digest(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
