package com.mall.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 资料修改请求：用户名不可修改；三个字段均可选，传 null 表示不修改该字段。
 */
public record UpdateProfileRequest(
        @Size(max = 20, message = "手机号长度不能超过 20")
        String phone,
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100")
        String email,
        @Size(max = 255, message = "头像地址长度不能超过 255")
        String avatar
) {
}
