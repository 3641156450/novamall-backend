package com.novamall.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置项。
 *
 * <p>生产环境的密钥必须走配置中心（Nacos）或环境变量注入，不要提交到 Git。
 * 这里给了默认值，是为了让项目 clone 下来就能跑。</p>
 *
 * @author NovaMall
 */
@Data
@ConfigurationProperties(prefix = "novamall.jwt")
public class JwtProperties {

    /** 签名密钥，HS256 要求至少 32 字节 */
    private String secret = "novamall-secret-key-please-change-in-production-env";

    /** 过期时间（毫秒），默认 2 小时 */
    private long expireMillis = 2 * 60 * 60 * 1000L;

    /** 记住我的过期时间（毫秒），默认 7 天 */
    private long rememberMeMillis = 7 * 24 * 60 * 60 * 1000L;

    /** 令牌请求头名 */
    private String header = "Authorization";

    /** 令牌前缀 */
    private String prefix = "Bearer ";
}
