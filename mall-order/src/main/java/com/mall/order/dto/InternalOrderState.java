package com.mall.order.dto;

/**
 * 内部接口返回的订单状态摘要：供商品服务对账任务判断孤儿扣减是否需要回补
 * （订单不存在或已 CANCELLED 才回补，PENDING/PAID 等状态说明库存被正常持有）。
 */
public record InternalOrderState(boolean exists, String status) {

    public static InternalOrderState missing() {
        return new InternalOrderState(false, null);
    }
}
