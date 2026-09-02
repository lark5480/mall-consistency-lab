package com.mall.user.dto;

import java.time.LocalDateTime;

public record UserProfileResponse(
        Long userId,
        String username,
        String phone,
        String email,
        String avatar,
        String role,
        LocalDateTime createdAt
) {
}
