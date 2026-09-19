package com.novamall.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应。
 *
 * <p>为什么不直接返回 User 实体？
 * 实体里含 password 哈希、deleted 等内部字段，一旦返回就是敏感信息泄露。
 * 必须做一层 VO 转换，这是最基本的安全意识（面试很容易被追问）。</p>
 *
 * @author NovaMall
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    /** token 剩余有效期（秒），前端用来做自动续期 */
    private Long expireIn;

    private Long userId;

    private String username;

    private String roleCode;
}
