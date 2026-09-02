package com.mall.product.task;

import com.mall.common.result.Result;
import com.mall.product.entity.StockDedupLog;
import com.mall.product.feign.OrderClient;
import com.mall.product.mapper.StockDedupLogMapper;
import com.mall.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrphanDeductionReconcileJobTest {
    private StockDedupLogMapper dedupLogMapper;
    private ProductService productService;
    private OrderClient orderClient;
    private OrphanDeductionReconcileJob job;

    @BeforeEach
    void setUp() {
        dedupLogMapper = Mockito.mock(StockDedupLogMapper.class);
        productService = Mockito.mock(ProductService.class);
        orderClient = Mockito.mock(OrderClient.class);
        job = new OrphanDeductionReconcileJob(dedupLogMapper, productService, orderClient, 10L, 7L);
    }

    private StockDedupLog deduction(String orderNo) {
        StockDedupLog record = new StockDedupLog();
        record.setOrderNo(orderNo);
        record.setProductId(2L);
        record.setCount(1);
        record.setStatus("DEDUCTED");
        return record;
    }

    @Test
    void shouldRestoreStockForOrphanDeduction() {
        Mockito.when(dedupLogMapper.selectList(Mockito.any())).thenReturn(List.of(deduction("ORD-ORPHAN-1")));
        Mockito.when(orderClient.orderState("ORD-ORPHAN-1"))
                .thenReturn(Result.ok(new OrderClient.OrderState(false, null)));
        Mockito.when(productService.restoreStock(2L, 1, "ORD-ORPHAN-1")).thenReturn(true);

        assertEquals(1, job.reconcileOnce());
        Mockito.verify(productService).restoreStock(2L, 1, "ORD-ORPHAN-1");
    }

    @Test
    void shouldSkipWhenOrderActuallyExists() {
        Mockito.when(dedupLogMapper.selectList(Mockito.any())).thenReturn(List.of(deduction("ORD-LIVE-1")));
        Mockito.when(orderClient.orderState("ORD-LIVE-1"))
                .thenReturn(Result.ok(new OrderClient.OrderState(true, "PENDING")));

        assertEquals(0, job.reconcileOnce());
        Mockito.verify(productService, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void shouldRestoreWhenOrderCancelledButRestoreFailed() {
        // 取消时远程回补失败的欠账：订单已 CANCELLED，对账按状态兜底回补
        Mockito.when(dedupLogMapper.selectList(Mockito.any())).thenReturn(List.of(deduction("ORD-CANCEL-1")));
        Mockito.when(orderClient.orderState("ORD-CANCEL-1"))
                .thenReturn(Result.ok(new OrderClient.OrderState(true, "CANCELLED")));
        Mockito.when(productService.restoreStock(2L, 1, "ORD-CANCEL-1")).thenReturn(true);

        assertEquals(1, job.reconcileOnce());
        Mockito.verify(productService).restoreStock(2L, 1, "ORD-CANCEL-1");
    }

    @Test
    void shouldSkipWhenOrderStateUncertain() {
        // 回归：旧实现把服务端业务错误（code!=0）视为"订单不存在"→ 可能误回补正常订单的库存；
        // 现在非确定答案一律跳过
        Mockito.when(dedupLogMapper.selectList(Mockito.any())).thenReturn(List.of(deduction("ORD-ERR-1")));
        Mockito.when(orderClient.orderState("ORD-ERR-1")).thenReturn(Result.error(500, "internal error"));

        assertEquals(0, job.reconcileOnce());
        Mockito.verify(productService, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void shouldTolerateOrderServiceFailure() {
        // 订单服务不可用：本轮跳过该记录，不抛异常、不误回补
        Mockito.when(dedupLogMapper.selectList(Mockito.any())).thenReturn(List.of(deduction("ORD-DOWN-1")));
        Mockito.when(orderClient.orderState("ORD-DOWN-1")).thenThrow(Mockito.mock(feign.FeignException.class));

        assertEquals(0, job.reconcileOnce());
        Mockito.verify(productService, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void shouldCleanupExpiredRestoredLogs() {
        Mockito.when(dedupLogMapper.delete(Mockito.any())).thenReturn(3);

        job.reconcile();

        Mockito.verify(dedupLogMapper).delete(Mockito.any());
    }
}
