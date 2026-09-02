package com.mall.user.controller;

import com.mall.common.exception.BizException;
import com.mall.common.result.Result;
import com.mall.common.result.ResultCode;
import com.mall.user.dto.AuthResponse;
import com.mall.user.dto.LoginRequest;
import com.mall.user.dto.RegisterRequest;
import com.mall.user.dto.UserResponse;
import com.mall.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserResponse> me(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(authService.me(userId));
    }
}
