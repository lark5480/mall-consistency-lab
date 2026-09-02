package com.mall.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.order.dto.OrderDTO;
import com.mall.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * B 端订单管理。网关对 /api/v1/admin/** 强制 ADMIN 角色，控制器内不再重复校验。
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword) {
        Page<OrderDTO> result = orderService.adminList(page, size, status, keyword);
        return Result.ok(Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", result.getCurrent(), "size", result.getSize()));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderDTO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.adminDetail(orderNo));
    }

    @PostMapping("/{orderNo}/ship")
    public Result<OrderDTO> ship(@PathVariable String orderNo) {
        return Result.ok(orderService.ship(orderNo));
    }
}
