package com.mall.order.dto;

import java.math.BigDecimal;

public record OrderItemDTO(
        Long productId,
        String productName,
        BigDecimal price,
        Integer count,
        String imageUrl
) {
}
