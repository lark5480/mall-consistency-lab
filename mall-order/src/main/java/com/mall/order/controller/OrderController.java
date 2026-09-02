package com.mall.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.Result;
import com.mall.order.dto.CreateOrderRequest;
import com.mall.order.dto.OrderDTO;
import com.mall.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<OrderDTO> create(@RequestHeader("X-User-Id") Long userId,
                                   @Valid @RequestBody CreateOrderRequest request) {
        return Result.ok(orderService.create(userId, request));
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestHeader("X-User-Id") Long userId,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        Page<OrderDTO> result = orderService.list(userId, page, size);
        return Result.ok(Map.of("list", result.getRecords(), "total", result.getTotal(),
                "page", result.getCurrent(), "size", result.getSize()));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderDTO> detail(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderNo) {
        return Result.ok(orderService.detail(userId, orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    public Result<OrderDTO> pay(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderNo) {
        return Result.ok(orderService.pay(userId, orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<OrderDTO> cancel(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderNo) {
        return Result.ok(orderService.cancel(userId, orderNo));
    }

    @PostMapping("/{orderNo}/complete")
    public Result<OrderDTO> complete(@RequestHeader("X-User-Id") Long userId, @PathVariable String orderNo) {
        return Result.ok(orderService.complete(userId, orderNo));
    }
}
