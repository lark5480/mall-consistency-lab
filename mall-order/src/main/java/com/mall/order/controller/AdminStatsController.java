package com.mall.order.controller;

import com.mall.common.result.Result;
import com.mall.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** B 端订单域统计（GMV 与趋势均不含已取消订单）。商品域统计见 mall-product。 */
@RestController
@RequestMapping("/api/v1/admin/stats/orders")
public class AdminStatsController {
    private final OrderService orderService;

    public AdminStatsController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Result<Map<String, Object>> summary() {
        return Result.ok(orderService.orderStats());
    }
}
