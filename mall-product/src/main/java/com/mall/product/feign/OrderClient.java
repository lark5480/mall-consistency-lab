package com.mall.product.feign;

import com.mall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 订单服务内部客户端：供孤儿扣减对账任务查询订单是否存在及其当前状态。
 * {@code OrderState} 是 mall-order 侧 InternalOrderState 的镜像定义——
 * 服务间有意不共享 DTO（见 docs/DECISIONS.md），字段变更需两侧同步。
 */
@FeignClient(name = "mall-order")
public interface OrderClient {

    @GetMapping("/internal/orders/{orderNo}/state")
    Result<OrderState> orderState(@PathVariable("orderNo") String orderNo);

    record OrderState(boolean exists, String status) {
    }
}
