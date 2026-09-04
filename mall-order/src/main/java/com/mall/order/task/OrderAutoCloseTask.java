package com.mall.order.task;

import com.mall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时自动关单任务：下单超过 mall.order.auto-close-minutes 分钟仍未支付的订单
 * 由系统批量置为已取消并回补库存（对齐主流电商平台的超时关单机制）。
 * 与手动取消同一 CAS 纪律：先条件更新抢终态、赢了才回补，输了零动作（详见 CONSISTENCY.md 三方竞态分析）。
 * fixedDelay 串行执行保证上一轮结束后才开始下一轮；单实例部署无需分布式锁。
 */
@Component
@Slf4j
public class OrderAutoCloseTask {
    private final OrderService orderService;

    public OrderAutoCloseTask(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${mall.order.auto-close-interval-ms:300000}",
            initialDelayString = "${mall.order.auto-close-initial-delay-ms:90000}")
    public void autoClose() {
        try {
            int count = orderService.autoCloseExpiredPendingOrders();
            if (count > 0) {
                log.info("Order auto-close round finished: closed={}", count);
            }
        } catch (Exception failure) {
            // 定时任务不允许抛出异常中断调度线程，记录后等下一轮重试；
            // 已 CAS 关单但回补失败的欠账由商品服务对账任务兜底
            log.error("Order auto-close round failed", failure);
        }
    }
}
