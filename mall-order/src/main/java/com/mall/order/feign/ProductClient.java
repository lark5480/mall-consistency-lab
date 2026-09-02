package com.mall.order.feign;

import com.mall.common.result.Result;
import com.mall.order.dto.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "mall-product")
public interface ProductClient {
    @GetMapping("/internal/products/{productId}")
    Result<ProductDTO> getProduct(@PathVariable("productId") Long productId);

    @PostMapping("/internal/products/{productId}/stock/decrease")
    Result<Boolean> decreaseStock(@PathVariable("productId") Long productId, @RequestBody Map<String, Object> request);

    @GetMapping("/internal/products/stock/status/{orderNo}")
    Result<String> stockStatus(@PathVariable("orderNo") String orderNo);

    @PostMapping("/internal/products/{productId}/stock/restore")
    Result<Boolean> restoreStock(@PathVariable("productId") Long productId, @RequestBody Map<String, Object> request);
}
