package com.mall.user.service;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.user.dto.ChangePasswordRequest;
import com.mall.user.dto.UpdateProfileRequest;
import com.mall.user.dto.UserProfileResponse;
import com.mall.user.entity.User;
import com.mall.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserServiceTest {
    private UserMapper userMapper;
    private UserService userService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userMapper = Mockito.mock(UserMapper.class);
        userService = new UserService(userMapper);
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash(encoder.encode("old-pass-123"));
        user.setPhone("13800000000");
        user.setEmail("demo@mall.local");
        user.setRole("USER");
        return user;
    }

    @Test
    void profileShouldExposeContactFields() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());
        UserProfileResponse response = userService.profile(1L);
        assertEquals("demo", response.username());
        assertEquals("13800000000", response.phone());
        assertEquals("USER", response.role());
    }

    @Test
    void updateProfileShouldPatchOnlyProvidedFields() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());

        UserProfileResponse response = userService.updateProfile(1L,
                new UpdateProfileRequest("13911112222", null, "https://cdn.example.com/a.png"));

        assertEquals("13911112222", response.phone());
        // email 未传：保持原值；avatar 已更新
        assertEquals("demo@mall.local", response.email());
        assertEquals("https://cdn.example.com/a.png", response.avatar());
        Mockito.verify(userMapper).updateById(Mockito.any(User.class));
    }

    @Test
    void updateProfileShouldClearFieldWhenBlank() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());

        UserProfileResponse response = userService.updateProfile(1L,
                new UpdateProfileRequest("", "new@mall.local", null));

        assertNull(response.phone());
        assertEquals("new@mall.local", response.email());
    }

    @Test
    void changePasswordShouldEncodeNewPassword() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());

        userService.changePassword(1L, new ChangePasswordRequest("old-pass-123", "new-pass-456"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        Mockito.verify(userMapper).updateById(captor.capture());
        assertEquals(true, encoder.matches("new-pass-456", captor.getValue().getPasswordHash()));
    }

    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());

        BizException exception = assertThrows(BizException.class,
                () -> userService.changePassword(1L, new ChangePasswordRequest("wrong-pass", "new-pass-456")));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
        Mockito.verify(userMapper, Mockito.never()).updateById(Mockito.any(User.class));
    }

    @Test
    void changePasswordShouldRejectSameAsCurrent() {
        Mockito.when(userMapper.selectById(1L)).thenReturn(user());

        BizException exception = assertThrows(BizException.class,
                () -> userService.changePassword(1L, new ChangePasswordRequest("old-pass-123", "old-pass-123")));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
    }
}
