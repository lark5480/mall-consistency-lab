package com.mall.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.Result;
import com.mall.order.dto.AddressDTO;
import com.mall.order.dto.CreateOrderRequest;
import com.mall.order.dto.ProductDTO;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.feign.ProductClient;
import com.mall.order.feign.UserClient;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderServiceTest {
    private static final String INSUFFICIENT_BODY =
            "{\"code\":40001,\"message\":\"库存不足\",\"data\":null}";

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductClient productClient;
    private UserClient userClient;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = Mockito.mock(OrderMapper.class);
        orderItemMapper = Mockito.mock(OrderItemMapper.class);
        productClient = Mockito.mock(ProductClient.class);
        userClient = Mockito.mock(UserClient.class);
        // 默认：任意用户取任意地址都返回有效地址（个别用例可覆盖）
        Mockito.when(userClient.getAddress(Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(Result.ok(address()));
        // 测试环境用 mock 事务管理器驱动 TransactionTemplate：getTransaction 返回空状态，commit/rollback 为空操作
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        Mockito.when(txManager.getTransaction(Mockito.any())).thenReturn(new SimpleTransactionStatus());
        orderService = new OrderService(orderMapper, orderItemMapper, productClient, userClient,
                new ObjectMapper(), new TransactionTemplate(txManager), 7L);
    }

    private AddressDTO address() {
        return new AddressDTO(3L, "张三", "13800000001", "上海市", "上海市", "浦东新区", "学习路 1 号", true, null);
    }

    private void stubAddress(Long userId, Long addressId) {
        Mockito.when(userClient.getAddress(userId, addressId)).thenReturn(Result.ok(address()));
    }

    private void stubOrderInsert() {
        Mockito.doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(99L);
            return 1;
        }).when(orderMapper).insert(Mockito.any(Order.class));
    }

    /** 状态 CAS 条件更新默认成功（影响 1 行），个别用例可覆盖为 0 模拟并发冲突 */
    private void stubCasSuccess() {
        Mockito.when(orderMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(1);
    }

    private Order pendingOrder(String orderNo) {
        Order order = new Order();
        order.setId(99L);
        order.setOrderNo(orderNo);
        order.setUserId(8L);
        order.setTotalAmount(BigDecimal.TEN);
        order.setStatus("PENDING");
        return order;
    }

    private void stubItems(Long orderId) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(1L);
        item.setProductName("Phone");
        item.setPrice(BigDecimal.TEN);
        item.setCount(2);
        Mockito.when(orderItemMapper.selectList(Mockito.any())).thenReturn(List.of(item));
    }

    @Test
    void shouldCreateOrderWhenStockIsEnough() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(productClient.decreaseStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenReturn(Result.ok(true));
        stubAddress(8L, 3L);
        stubOrderInsert();

        orderService.create(8L, new CreateOrderRequest(1L, 2, 3L));

        // 先扣减库存，再持久化订单（含地址快照）
        Mockito.verify(productClient).decreaseStock(Mockito.eq(1L), Mockito.anyMap());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        Mockito.verify(orderMapper).insert(captor.capture());
        assertEquals("张三", captor.getValue().getReceiverName());
        Mockito.verify(orderItemMapper).insert(Mockito.any(com.mall.order.entity.OrderItem.class));
    }

    @Test
    void shouldRejectCreateWhenAddressNotOwned() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(userClient.getAddress(8L, 777L)).thenReturn(Result.ok(null));

        BizException exception = assertThrows(BizException.class,
                () -> orderService.create(8L, new CreateOrderRequest(1L, 1, 777L)));
        assertEquals(400, exception.getCode());
        Mockito.verify(productClient, Mockito.never()).decreaseStock(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test
    void shouldThrowWhenStockIsInsufficientBeforeDeducting() {
        ProductDTO product = new ProductDTO(2L, "Hot Item", null, BigDecimal.TEN, 5, 1L, null, "ON_SALE", null);
        Mockito.when(productClient.getProduct(2L)).thenReturn(Result.ok(product));

        assertThrows(BizException.class,
                () -> orderService.create(8L, new CreateOrderRequest(2L, 6, 3L)));
        Mockito.verify(productClient, Mockito.never()).decreaseStock(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test
    void shouldTranslateInsufficientStockConflictFromProduct() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        FeignException conflict = Mockito.mock(FeignException.class);
        Mockito.when(conflict.status()).thenReturn(409);
        Mockito.when(conflict.contentUTF8()).thenReturn(INSUFFICIENT_BODY);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(productClient.decreaseStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenThrow(conflict);

        BizException exception = assertThrows(BizException.class,
                () -> orderService.create(8L, new CreateOrderRequest(1L, 2, 3L)));
        assertEquals(40001, exception.getCode());
        assertEquals("库存不足", exception.getMessage());
        Mockito.verify(orderMapper, Mockito.never()).insert(Mockito.any(Order.class));
    }

    @Test
    void shouldContinueWhenFeignTimesOutButStockAlreadyDeducted() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        FeignException timeout = Mockito.mock(FeignException.class);
        Mockito.when(timeout.status()).thenReturn(503);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(productClient.decreaseStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenThrow(timeout);
        Mockito.when(productClient.stockStatus(Mockito.anyString())).thenReturn(Result.ok("DEDUCTED"));
        stubAddress(8L, 3L);
        stubOrderInsert();

        orderService.create(8L, new CreateOrderRequest(1L, 2, 3L));

        Mockito.verify(orderMapper).insert(Mockito.any(Order.class));
        Mockito.verify(productClient, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test
    void shouldFailWhenFeignTimesOutAndStockNotDeducted() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        FeignException timeout = Mockito.mock(FeignException.class);
        Mockito.when(timeout.status()).thenReturn(503);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(productClient.decreaseStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenThrow(timeout);
        Mockito.when(productClient.stockStatus(Mockito.anyString())).thenReturn(Result.ok("NONE"));

        BizException exception = assertThrows(BizException.class,
                () -> orderService.create(8L, new CreateOrderRequest(1L, 2, 3L)));
        assertEquals(500, exception.getCode());
        assertEquals("库存服务暂不可用", exception.getMessage());
        Mockito.verify(orderMapper, Mockito.never()).insert(Mockito.any(Order.class));
        // 状态查询成功且确认未扣减：无需补偿，避免盲目调用
        Mockito.verify(productClient, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test
    void shouldCompensateStockWhenOrderPersistFails() {
        ProductDTO product = new ProductDTO(1L, "Phone", null, BigDecimal.TEN, 100, 1L, null, "ON_SALE", null);
        Mockito.when(productClient.getProduct(1L)).thenReturn(Result.ok(product));
        Mockito.when(productClient.decreaseStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenReturn(Result.ok(true));
        stubAddress(8L, 3L);
        Mockito.when(orderMapper.insert(Mockito.any(Order.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> orderService.create(8L, new CreateOrderRequest(1L, 2, 1L)));

        Mockito.verify(productClient).restoreStock(Mockito.eq(1L), Mockito.anyMap());
        Mockito.verify(orderItemMapper, Mockito.never())
                .insert(Mockito.any(com.mall.order.entity.OrderItem.class));
    }

    @Test
    void cancelShouldMarkCancelledFirstThenRestoreStock() {
        Order order = pendingOrder("ORD-CANCEL-1");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(order);
        stubItems(99L);
        Mockito.when(productClient.restoreStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenReturn(Result.ok(true));
        stubCasSuccess();

        var cancelled = orderService.cancel(8L, "ORD-CANCEL-1");

        assertEquals("CANCELLED", cancelled.status());
        Mockito.verify(productClient).restoreStock(Mockito.eq(1L), Mockito.anyMap());
        // 状态流转必须走条件更新（CAS），不允许无条件覆盖写
        Mockito.verify(orderMapper).update(Mockito.isNull(), Mockito.any());
        // 顺序锁定：先本地 CAS 落 CANCELLED，再远程回补库存。
        // 旧顺序（先回补后 CAS）在并发 pay 抢先成功时会留下"已支付订单 + 凭空多出的库存"
        InOrder inOrder = Mockito.inOrder(orderMapper, productClient);
        inOrder.verify(orderMapper).update(Mockito.isNull(), Mockito.any());
        inOrder.verify(productClient).restoreStock(Mockito.eq(1L), Mockito.anyMap());
    }

    @Test
    void cancelShouldStillCancelWhenRestoreFails() {
        // 回补失败不阻断取消：订单已终态，回补欠账由商品服务对账任务按「订单已 CANCELLED」兜底
        Order order = pendingOrder("ORD-CANCEL-2");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(order);
        stubItems(99L);
        Mockito.when(productClient.restoreStock(Mockito.eq(1L), ArgumentMatchers.anyMap()))
                .thenThrow(Mockito.mock(FeignException.class));
        stubCasSuccess();

        var cancelled = orderService.cancel(8L, "ORD-CANCEL-2");

        assertEquals("CANCELLED", cancelled.status());
        Mockito.verify(orderMapper).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void cancelShouldRejectNonPendingOrder() {
        Order paid = pendingOrder("ORD-PAID-1");
        paid.setStatus("PAID");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(paid);

        BizException exception = assertThrows(BizException.class,
                () -> orderService.cancel(8L, "ORD-PAID-1"));
        assertEquals(40002, exception.getCode());
        Mockito.verify(productClient, Mockito.never()).restoreStock(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test
    void shipShouldMovePaidToShipped() {
        Order paid = pendingOrder("ORD-SHIP-1");
        paid.setStatus("PAID");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(paid);
        stubCasSuccess();

        var shipped = orderService.ship("ORD-SHIP-1");
        assertEquals("SHIPPED", shipped.status());
    }

    @Test
    void shipShouldRejectPendingOrder() {
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(pendingOrder("ORD-SHIP-2"));

        BizException exception = assertThrows(BizException.class, () -> orderService.ship("ORD-SHIP-2"));
        assertEquals(40002, exception.getCode());
    }

    @Test
    void completeShouldMoveShippedToCompleted() {
        Order shipped = pendingOrder("ORD-COMPLETE-1");
        shipped.setStatus("SHIPPED");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(shipped);
        stubCasSuccess();

        var completed = orderService.complete(8L, "ORD-COMPLETE-1");

        assertEquals("COMPLETED", completed.status());
        Mockito.verify(orderMapper).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void payShouldFailWhenStateConcurrentlyChanged() {
        // 模拟竞态：读取时是 PENDING，CAS 写入前状态已被并发修改（影响 0 行）
        Order stale = pendingOrder("ORD-CAS-PAY");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(stale);
        Mockito.when(orderMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(0);

        BizException exception = assertThrows(BizException.class, () -> orderService.pay(8L, "ORD-CAS-PAY"));
        assertEquals(40002, exception.getCode());
    }

    @Test
    void stateByOrderNoShouldReportExistenceAndStatus() {
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(pendingOrder("ORD-STATE-1"));
        var state = orderService.stateByOrderNo("ORD-STATE-1");
        assertTrue(state.exists());
        assertEquals("PENDING", state.status());

        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(null);
        assertFalse(orderService.stateByOrderNo("ORD-STATE-0").exists());
    }

    @Test
    void completeShouldRejectPaidOrder() {
        Order paid = pendingOrder("ORD-COMPLETE-2");
        paid.setStatus("PAID");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(paid);

        BizException exception = assertThrows(BizException.class,
                () -> orderService.complete(8L, "ORD-COMPLETE-2"));
        // 未发货不允许确认收货：PAID→COMPLETED 非法流转
        assertEquals(40002, exception.getCode());
    }

    @Test
    void completeShouldRejectOtherUsersOrder() {
        Order shipped = pendingOrder("ORD-COMPLETE-3");
        shipped.setStatus("SHIPPED");
        Mockito.when(orderMapper.selectOne(Mockito.any())).thenReturn(shipped);

        assertThrows(BizException.class, () -> orderService.complete(999L, "ORD-COMPLETE-3"));
        Mockito.verify(orderMapper, Mockito.never()).updateById(Mockito.any(Order.class));
    }

    @Test
    void autoCompleteShouldCompleteExpiredShippedOrders() {
        Order expired = pendingOrder("ORD-AUTO-1");
        expired.setStatus("SHIPPED");
        Mockito.when(orderMapper.selectList(Mockito.any())).thenReturn(List.of(expired));
        Mockito.when(orderMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(1);

        int completed = orderService.autoCompleteExpiredOrders();

        assertEquals(1, completed);
        Mockito.verify(orderMapper).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void autoCompleteShouldSkipConflictedRow() {
        // 与手动确认收货并发时条件更新影响行数为 0：跳过且不计入完成数
        Order conflicted = pendingOrder("ORD-AUTO-2");
        conflicted.setStatus("SHIPPED");
        Mockito.when(orderMapper.selectList(Mockito.any())).thenReturn(List.of(conflicted));
        Mockito.when(orderMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(0);

        assertEquals(0, orderService.autoCompleteExpiredOrders());
    }
}
