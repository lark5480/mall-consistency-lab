package com.mall.user.controller;

import com.mall.common.result.Result;
import com.mall.user.dto.ChangePasswordRequest;
import com.mall.user.dto.UpdateProfileRequest;
import com.mall.user.dto.UserProfileResponse;
import com.mall.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<UserProfileResponse> profile(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(userService.profile(userId));
    }

    @PutMapping
    public Result<UserProfileResponse> update(@RequestHeader("X-User-Id") Long userId,
                                              @Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(userId, request));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestHeader("X-User-Id") Long userId,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return Result.ok();
    }
}
