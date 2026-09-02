package com.mall.user.controller;

import com.mall.common.result.Result;
import com.mall.user.dto.AddressResponse;
import com.mall.user.service.AddressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供服务内网调用（网关对 /internal/** 全量拦截，外部不可达）：
 * 订单服务下单时经 Feign 取地址快照。
 */
@RestController
@RequestMapping("/internal/users/{userId}/addresses")
public class InternalUserController {
    private final AddressService addressService;

    public InternalUserController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/{addressId}")
    public Result<AddressResponse> address(@PathVariable Long userId, @PathVariable Long addressId) {
        return Result.ok(addressService.ownedAddress(userId, addressId));
    }
}
