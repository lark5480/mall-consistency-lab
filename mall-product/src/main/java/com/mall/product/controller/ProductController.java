package com.mall.product.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.product.dto.ProductDTO;
import com.mall.product.dto.ProductRequest;
import com.mall.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String keyword) {
        Page<ProductDTO> result = productService.list(page, size, categoryId, keyword);
        return Result.ok(Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", result.getCurrent(), "size", result.getSize()));
    }

    @GetMapping("/{productId}")
    public Result<ProductDTO> detail(@PathVariable Long productId) {
        return Result.ok(productService.detail(productId));
    }

    @PostMapping
    public Result<ProductDTO> create(@Valid @RequestBody ProductRequest request) {
        return Result.ok(productService.create(request));
    }

    @PutMapping("/{productId}")
    public Result<ProductDTO> update(@PathVariable Long productId, @Valid @RequestBody ProductRequest request) {
        return Result.ok(productService.update(productId, request));
    }

    @DeleteMapping("/{productId}")
    public Result<Void> delete(@PathVariable Long productId) {
        productService.delete(productId);
        return Result.ok();
    }
}
