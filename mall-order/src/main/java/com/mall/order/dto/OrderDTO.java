package com.mall.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单对外视图。items 仅详情接口填充（列表为 null 以减少载荷）；
 * receiver* 为下单时固化的收货人快照，与 mall_user.address 解耦。
 */
public record OrderDTO(
        String orderNo,
        String status,
        BigDecimal totalAmount,
        Long addressId,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        Long userId,
        List<OrderItemDTO> items,
        LocalDateTime createdAt
) {
}
