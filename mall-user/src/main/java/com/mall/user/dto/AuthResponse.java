package com.mall.user.dto;

public record AuthResponse(String token, Long userId, String username, String role) {
}
