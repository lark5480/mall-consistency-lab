package com.mall.product.controller;

import com.mall.common.result.Result;
import com.mall.product.dto.ProductDTO;
import com.mall.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
public class InternalProductController {
    private final ProductService productService;

    public InternalProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{productId}")
    public Result<ProductDTO> detail(@PathVariable Long productId) {
        return Result.ok(productService.detail(productId));
    }

    @PostMapping("/{productId}/stock/decrease")
    public Result<Boolean> decrease(@PathVariable Long productId, @Valid @RequestBody StockDecreaseRequest request) {
        return Result.ok(productService.decreaseStock(productId, request.count(), request.orderNo()));
    }

    @GetMapping("/stock/status/{orderNo}")
    public Result<String> stockStatus(@PathVariable String orderNo) {
        return Result.ok(productService.stockStatus(orderNo));
    }

    @PostMapping("/{productId}/stock/restore")
    public Result<Boolean> restore(@PathVariable Long productId, @Valid @RequestBody StockDecreaseRequest request) {
        return Result.ok(productService.restoreStock(productId, request.count(), request.orderNo()));
    }

    /**
     * 库存是资金路径，内部接口也必须校验入参：
     * count 无下限时传负数会绕过 stock &lt; count 检查直接增加库存。
     */
    public record StockDecreaseRequest(
            @Min(value = 1, message = "库存变动数量必须为正整数") int count,
            @NotBlank(message = "订单号不能为空") String orderNo) {
    }
}
