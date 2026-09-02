package com.mall.order.feign;

import com.mall.common.result.Result;
import com.mall.order.dto.AddressDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "mall-user")
public interface UserClient {
    @GetMapping("/internal/users/{userId}/addresses/{addressId}")
    Result<AddressDTO> getAddress(@PathVariable("userId") Long userId, @PathVariable("addressId") Long addressId);
}
