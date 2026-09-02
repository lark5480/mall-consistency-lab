package com.mall.order.dto;

import java.time.LocalDateTime;

/** 对齐 mall-user AddressResponse 的最小快照结构（跨服务手写 DTO，保持契约耦合）。 */
public record AddressDTO(
        Long id,
        String receiver,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        boolean isDefault,
        LocalDateTime createdAt
) {
    public String fullAddress() {
        return province + " " + city + " " + district + " " + detail;
    }
}
