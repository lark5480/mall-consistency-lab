package com.mall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {
    @BeforeAll
    static void configureKey() {
        // 无 Spring 容器的纯单测：显式注入测试密钥（生产由 JwtSecurityInitializer 在启动时配置）
        JwtUtil.configure("test-secret-key-for-unit-tests-0123456789abcdef", 3600_000L);
    }

    @Test
    void shouldGenerateAndParseToken() {
        String token = JwtUtil.generateToken(18L, "tester", "USER");
        Claims claims = JwtUtil.parseToken(token);
        assertEquals("18", claims.getSubject());
        assertEquals("tester", claims.get("username"));
    }

    @Test
    void shouldContainRoleClaim() {
        Claims userClaims = JwtUtil.parseToken(JwtUtil.generateToken(18L, "tester", "USER"));
        assertEquals("USER", userClaims.get("role", String.class));

        Claims adminClaims = JwtUtil.parseToken(JwtUtil.generateToken(1L, "admin", "ADMIN"));
        assertEquals("ADMIN", adminClaims.get("role", String.class));
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = JwtUtil.generateToken(18L, "tester", "USER") + "x";
        assertThrows(JwtException.class, () -> JwtUtil.parseToken(token));
    }

    @Test
    void shouldRejectExpiredToken() {
        String token = JwtUtil.generateToken(18L, "tester", "USER", -1000L);
        assertThrows(ExpiredJwtException.class, () -> JwtUtil.parseToken(token));
    }
}
