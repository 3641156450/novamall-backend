package com.novamall.auth.controller;

import com.novamall.auth.dto.LoginRequest;
import com.novamall.auth.dto.LoginResponse;
import com.novamall.auth.dto.RegisterRequest;
import com.novamall.auth.service.AuthService;
import com.novamall.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。
 *
 * <p>RESTful 设计说明：登录本质是"创建一个会话"，严格来说应该用
 * POST /api/sessions 而不是 POST /api/auth/login。
 * 但前者对前端和网关白名单配置都不友好，工程上普遍还是用后者，
 * 属于"规范向可读性妥协"的典型案例（面试能聊这个说明你懂权衡）。</p>
 *
 * @author NovaMall
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证中心", description = "注册 / 登录 / 退出")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public R<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录（JWT 加入黑名单）")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(stripBearer(authorization));
        return R.ok();
    }

    @GetMapping("/check")
    @Operation(summary = "校验 token 是否有效")
    public R<Boolean> check(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        return R.ok(userId != null);
    }

    private String stripBearer(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return authorization;
    }
}
