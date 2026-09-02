package com.mall.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.common.util.JwtUtil;
import com.mall.user.dto.AuthResponse;
import com.mall.user.dto.LoginRequest;
import com.mall.user.dto.RegisterRequest;
import com.mall.user.dto.UserResponse;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public AuthResponse register(RegisterRequest request) {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(1);
        user.setRole("USER");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "用户名已存在");
        }
        return createToken(user.getId(), user.getUsername(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        return createToken(user.getId(), user.getUsername(), user.getRole());
    }

    public UserResponse me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return new UserResponse(user.getId(), user.getUsername(), user.getRole());
    }

    private AuthResponse createToken(Long userId, String username, String role) {
        return new AuthResponse(JwtUtil.generateToken(userId, username, role), userId, username, role);
    }
}
