package com.mall.order.task;

import com.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时自动确认收货任务：发货超过 mall.order.auto-complete-days 天仍未确认的订单
 * 由系统批量置为已完成（对齐主流电商平台的自动确认收货机制）。
 * fixedDelay 串行执行保证上一轮结束后才开始下一轮；单实例部署无需分布式锁。
 */
@Component
@Slf4j
public class OrderAutoCompleteTask {
    private final OrderService orderService;

    public OrderAutoCompleteTask(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${mall.order.auto-complete-interval-ms:300000}",
            initialDelayString = "${mall.order.auto-complete-initial-delay-ms:60000}")
    public void autoComplete() {
        try {
            int count = orderService.autoCompleteExpiredOrders();
            if (count > 0) {
                log.info("Order auto-complete round finished: completed={}", count);
            }
        } catch (Exception failure) {
            // 定时任务不允许抛出异常中断调度线程，记录后等下一轮重试
            log.error("Order auto-complete round failed", failure);
        }
    }
}
