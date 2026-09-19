package com.novamall.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p>参数校验用 jakarta.validation 注解（注意：Spring Boot 3 起包名从 javax 变成了 jakarta，
 * 这是从 Spring Boot 2 迁移时最容易踩的坑之一）。</p>
 *
 * @author NovaMall
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4~20 位之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 32, message = "密码长度需在 8~32 位之间")
    private String password;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @jakarta.validation.constraints.Email(message = "邮箱格式不正确")
    private String email;
}
