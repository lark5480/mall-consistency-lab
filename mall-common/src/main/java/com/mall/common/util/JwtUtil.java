package com.mall.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具。密钥不提供任何内置兜底默认值：必须由 {@link JwtSecurityInitializer} 在启动阶段
 * 调用 {@link #configure} 显式注入（来源：JWT_SECRET 环境变量 / mall.jwt.secret 配置项），
 * 缺失或强度不足（HS256 要求至少 256 位）时应用直接启动失败，
 * 杜绝生产环境漏配时静默降级到可被猜测的固定密钥。
 * 单元测试没有 Spring 容器，可在 @BeforeAll 中显式调用 configure。
 */
public final class JwtUtil {
    public static final long DEFAULT_EXPIRE_MILLIS = 24 * 60 * 60 * 1000L;

    private static volatile SecretKey key;
    private static volatile long expireMillis = DEFAULT_EXPIRE_MILLIS;

    private JwtUtil() {
    }

    public static void configure(String secret, long ttlMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret 未配置：请通过环境变量 JWT_SECRET（或配置项 mall.jwt.secret）提供，拒绝以默认密钥静默启动");
        }
        try {
            key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        } catch (WeakKeyException weakKey) {
            throw new IllegalStateException("JWT secret 强度不足：HS256 要求至少 32 字节（256 位）", weakKey);
        }
        if (ttlMillis > 0) {
            expireMillis = ttlMillis;
        }
    }

    public static String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, expireMillis);
    }

    public static String generateToken(Long userId, String username, String role, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key())
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    private static SecretKey key() {
        SecretKey configured = key;
        if (configured == null) {
            throw new IllegalStateException("JwtUtil 尚未配置密钥：JWT_SECRET 未注入，拒绝签发/校验 Token");
        }
        return configured;
    }
}
