package com.mall.order.dto;

import java.math.BigDecimal;

/** 近 7 日下单趋势单点（GMV 不含已取消订单）。 */
public record TrendPoint(
        String date,
        long count,
        BigDecimal gmv
) {
}
