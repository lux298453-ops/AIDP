package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.request.LoginRequest;
import com.example.aidocumentplatform.model.dto.request.RegisterRequest;
import com.example.aidocumentplatform.model.dto.response.LoginResponse;
import com.example.aidocumentplatform.model.dto.response.Result;
import com.example.aidocumentplatform.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 —— 处理用户注册和登录。
 *
 * 接口列表:
 *   POST /api/auth/register — 用户注册
 *   POST /api/auth/login    — 用户登录（返回 JWT token）
 *   GET  /api/health        — 健康检查（无需认证）
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ==================== 认证接口 ====================

    /**
     * 用户注册。
     * 校验用户名唯一性，密码使用 BCrypt 加密后入库。
     */
    @PostMapping("/api/auth/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.ok();
    }

    /**
     * 用户登录。
     * 校验用户名密码，成功后签发 JWT token。
     *
     * @return 包含 token、userId、username、nickname 的响应
     */
    @PostMapping("/api/auth/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Result.ok(response);
    }

    // ==================== 健康检查（白名单，无需认证） ====================

    /**
     * 健康检查接口 —— 用于验证服务是否正常运行。
     * 已在 SecurityConfig 中配置为白名单，无需 Token 即可访问。
     */
    @GetMapping("/api/health")
    public Result<String> health() {
        return Result.ok("OK");
    }
}
