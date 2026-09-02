package com.mall.order.controller;

import com.mall.common.result.Result;
import com.mall.order.dto.InternalOrderState;
import com.mall.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口：仅供其他微服务（如商品服务对账）通过注册中心直连调用。
 * 网关只路由 /api/**，该路径对外部请求不可达，与 InternalProductController 对等。
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {
    private final OrderService orderService;

    public InternalOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderNo}/state")
    public Result<InternalOrderState> state(@PathVariable String orderNo) {
        return Result.ok(orderService.stateByOrderNo(orderNo));
    }
}
