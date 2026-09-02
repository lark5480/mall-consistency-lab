package com.mall.user.service;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.common.util.JwtUtil;
import com.mall.user.dto.AuthResponse;
import com.mall.user.dto.LoginRequest;
import com.mall.user.dto.RegisterRequest;
import com.mall.user.dto.UserResponse;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceTest {
    private UserMapper userMapper;
    private AuthService authService;

    @BeforeAll
    static void configureJwt() {
        // 无 Spring 容器的纯单测：显式注入测试密钥（生产由 JwtSecurityInitializer 在启动时配置）
        JwtUtil.configure("test-secret-key-for-unit-tests-0123456789abcdef", 3600_000L);
    }

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        authService = new AuthService(userMapper);
    }

    @Test
    void registerShouldReturnUserIdRoleAndToken() {
        Mockito.when(userMapper.insert(Mockito.any(User.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, User.class).setId(7L);
            return 1;
        });
        AuthResponse response = authService.register(new RegisterRequest("newbie", "pw123456"));
        assertEquals(7L, response.userId());
        assertEquals("USER", response.role());
        assertFalse(response.token().isBlank());
    }

    @Test
    void registerDuplicateUsernameShouldReturn409() {
        Mockito.when(userMapper.insert(Mockito.any(User.class)))
                .thenThrow(new DuplicateKeyException("uk_user_username"));
        BizException exception = assertThrows(BizException.class,
                () -> authService.register(new RegisterRequest("demo", "pw123456")));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
        assertEquals("用户名已存在", exception.getMessage());
    }

    @Test
    void loginWithWrongPasswordShouldReturn401() {
        User user = new User();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("right-pass"));
        user.setRole("USER");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(user);

        BizException exception = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("demo", "wrong-pass")));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), exception.getCode());
    }

    @Test
    void loginSuccessShouldEchoRole() {
        User admin = new User();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setPasswordHash(new BCryptPasswordEncoder().encode("admin123"));
        admin.setRole("ADMIN");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(admin);

        AuthResponse response = authService.login(new LoginRequest("admin", "admin123"));
        assertEquals("ADMIN", response.role());
        assertFalse(response.token().isBlank());
    }

    @Test
    void meShouldReturnRole() {
        User admin = new User();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        Mockito.when(userMapper.selectById(2L)).thenReturn(admin);

        UserResponse response = authService.me(2L);
        assertEquals(2L, response.userId());
        assertEquals("ADMIN", response.role());
    }

    @Test
    void meMissingShouldReturn404() {
        Mockito.when(userMapper.selectById(404L)).thenReturn(null);
        BizException exception = assertThrows(BizException.class, () -> authService.me(404L));
        assertEquals(ResultCode.NOT_FOUND.getCode(), exception.getCode());
    }
}
