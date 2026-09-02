package com.mall.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressSaveRequest(
        @NotBlank(message = "收货人不能为空")
        @Size(max = 50)
        String receiver,
        @NotBlank(message = "联系电话不能为空")
        @Size(min = 5, max = 20, message = "联系电话长度需在 5-20 位之间")
        String phone,
        @NotBlank(message = "省份不能为空")
        @Size(max = 30)
        String province,
        @NotBlank(message = "城市不能为空")
        @Size(max = 30)
        String city,
        @NotBlank(message = "区县不能为空")
        @Size(max = 30)
        String district,
        @NotBlank(message = "详细地址不能为空")
        @Size(max = 200)
        String detail,
        Boolean isDefault
) {
    public boolean defaultRequested() {
        return Boolean.TRUE.equals(isDefault);
    }
}
