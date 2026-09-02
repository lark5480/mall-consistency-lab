package com.mall.product.controller;

import com.mall.common.result.Result;
import com.mall.product.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** B 端商品域统计。订单域统计见 mall-order 的 /api/v1/admin/stats/orders。 */
@RestController
@RequestMapping("/api/v1/admin/stats/products")
public class AdminProductStatsController {
    private final CategoryService categoryService;

    public AdminProductStatsController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<Map<String, Object>> summary() {
        return Result.ok(categoryService.productStats());
    }
}
