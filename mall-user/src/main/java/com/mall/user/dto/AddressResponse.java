package com.mall.user.dto;

import java.time.LocalDateTime;

public record AddressResponse(
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
