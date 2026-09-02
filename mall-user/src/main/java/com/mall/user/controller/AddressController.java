package com.mall.user.controller;

import com.mall.common.result.Result;
import com.mall.user.dto.AddressResponse;
import com.mall.user.dto.AddressSaveRequest;
import com.mall.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me/addresses")
public class AddressController {
    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public Result<List<AddressResponse>> list(@RequestHeader("X-User-Id") Long userId) {
        return Result.ok(addressService.list(userId));
    }

    @PostMapping
    public Result<AddressResponse> create(@RequestHeader("X-User-Id") Long userId,
                                          @Valid @RequestBody AddressSaveRequest request) {
        return Result.ok(addressService.create(userId, request));
    }

    @PutMapping("/{addressId}")
    public Result<AddressResponse> update(@RequestHeader("X-User-Id") Long userId,
                                          @PathVariable Long addressId,
                                          @Valid @RequestBody AddressSaveRequest request) {
        return Result.ok(addressService.update(userId, addressId, request));
    }

    @DeleteMapping("/{addressId}")
    public Result<Void> delete(@RequestHeader("X-User-Id") Long userId, @PathVariable Long addressId) {
        addressService.delete(userId, addressId);
        return Result.ok();
    }
}
