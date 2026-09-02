package com.mall.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategorySaveRequest(
        @NotBlank(message = "分类名不能为空")
        @Size(max = 50, message = "分类名长度不能超过 50")
        String name
) {
}
