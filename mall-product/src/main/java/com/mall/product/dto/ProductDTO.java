package com.mall.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductDTO(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Long categoryId,
        String imageUrl,
        String status,
        LocalDateTime createdAt
) {
}
