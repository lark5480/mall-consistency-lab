package com.mall.user.service;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.user.dto.ChangePasswordRequest;
import com.mall.user.dto.UpdateProfileRequest;
import com.mall.user.dto.UserProfileResponse;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserProfileResponse profile(Long userId) {
        return toDto(requiredUser(userId));
    }

    /** 用户名不可修改；仅更新传入的非空字段。 */
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requiredUser(userId);
        if (request.phone() != null) {
            user.setPhone(request.phone().isBlank() ? null : request.phone().trim());
        }
        if (request.email() != null) {
            user.setEmail(request.email().isBlank() ? null : request.email().trim());
        }
        if (request.avatar() != null) {
            user.setAvatar(request.avatar().isBlank() ? null : request.avatar().trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return toDto(user);
    }

    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requiredUser(userId);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "旧密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private User requiredUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return user;
    }

    private UserProfileResponse toDto(User user) {
        return new UserProfileResponse(user.getId(), user.getUsername(), user.getPhone(),
                user.getEmail(), user.getAvatar(), user.getRole(), user.getCreatedAt());
    }
}
