package com.mall.order.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.Result;
import com.mall.common.result.ResultCode;
import com.mall.order.dto.AddressDTO;
import com.mall.order.dto.CreateOrderRequest;
import com.mall.order.dto.InternalOrderState;
import com.mall.order.dto.OrderDTO;
import com.mall.order.dto.OrderItemDTO;
import com.mall.order.dto.ProductDTO;
import com.mall.order.dto.TrendPoint;
import com.mall.order.entity.Order;
import com.mall.order.entity.OrderItem;
import com.mall.order.entity.OrderStatus;
import com.mall.order.feign.ProductClient;
import com.mall.order.feign.UserClient;
import com.mall.order.mapper.OrderItemMapper;
import com.mall.order.mapper.OrderMapper;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderService {
    private static final DateTimeFormatter TREND_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 单轮自动确认收货的最大处理量，防止极端积压时一次事务过长 */
    private static final long AUTO_COMPLETE_BATCH = 100;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductClient productClient;
    private final UserClient userClient;
    private final ObjectMapper objectMapper;
    /** 编程式事务：仅包裹本地落库动作，避免远程调用被圈进大事务长期占用连接 */
    private final TransactionTemplate transactionTemplate;
    /** 发货后超过该天数仍未确认收货则自动完成 */
    private final long autoCompleteDays;
    /** 下单后超过该分钟数仍未支付则自动关单 */
    private final long autoCloseMinutes;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                        ProductClient productClient, UserClient userClient, ObjectMapper objectMapper,
                        TransactionTemplate transactionTemplate,
                        @Value("${mall.order.auto-complete-days:7}") long autoCompleteDays,
                        @Value("${mall.order.auto-close-minutes:30}") long autoCloseMinutes) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productClient = productClient;
        this.userClient = userClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.autoCompleteDays = autoCompleteDays;
        this.autoCloseMinutes = autoCloseMinutes;
    }

    /**
     * 下单编排（方法本身无本地事务）：远程调用（查商品/查地址/扣库存）全部完成后，
     * 仅用 TransactionTemplate 包裹最后的落库动作，Feign 调用期间不再占用数据库连接；
     * 落库失败时补偿库存。跨服务一致性 = 库存侧幂等去重 + 本地事务 + 失败补偿 + 定时对账兜底。
     */
    public OrderDTO create(Long userId, CreateOrderRequest request) {
        ProductDTO product = loadProduct(request.productId());
        if (product.stock() < request.count()) {
            throw new BizException(ResultCode.INSUFFICIENT_STOCK);
        }
        AddressDTO address = loadAddress(userId, request.addressId());
        String orderNo = "ORD" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        deductStockOrThrow(product.id(), request.count(), orderNo);
        try {
            return transactionTemplate.execute(status -> persistOrder(userId, product, address, request, orderNo));
        } catch (RuntimeException exception) {
            log.error("Order persistence failed, compensating stock: orderNo={}, productId={}",
                    orderNo, product.id(), exception);
            compensateQuietly(product.id(), request.count(), orderNo);
            throw exception;
        }
    }

    /**
     * C 端取消：仅 PENDING 可取消。
     * 顺序必须是「先本地 CAS 落 CANCELLED，再远程回补库存」：
     * 若沿用 create 的「先远程后本地」，并发 pay 可在回补完成后抢先 CAS 成 PAID，
     * 取消侧 409 退出，但库存已回补且 dedup 已 RESTORED，再无任何机制扣回，
     * 形成"已支付订单 + 凭空多出的库存"；先 CAS 则同一订单的支付/取消必有一个赢家。
     * 与 create 相同：Feign 期间不持有 DB 连接（CAS 用 TransactionTemplate 单独包裹）。
     * 回补失败不再中止取消：订单已终态，dedup 记录仍为 DEDUCTED，
     * 由商品服务对账任务按「订单已 CANCELLED」兜底回补，最终一致。
     */
    public OrderDTO cancel(Long userId, String orderNo) {
        Order order = requiredOrder(userId, orderNo);
        assertTransition(order, OrderStatus.CANCELLED);
        OrderDTO cancelled = transactionTemplate.execute(status -> transition(order, OrderStatus.CANCELLED));
        restoreStockQuietly(order);
        return cancelled;
    }

    /** C 端支付：仅 PENDING 可支付。 */
    @Transactional
    public OrderDTO pay(Long userId, String orderNo) {
        Order order = requiredOrder(userId, orderNo);
        assertTransition(order, OrderStatus.PAID);
        return transition(order, OrderStatus.PAID);
    }

    /** C 端确认收货：仅 SHIPPED 可完成，订单终态。 */
    @Transactional
    public OrderDTO complete(Long userId, String orderNo) {
        Order order = requiredOrder(userId, orderNo);
        assertTransition(order, OrderStatus.COMPLETED);
        return transition(order, OrderStatus.COMPLETED);
    }

    /** B 端发货：仅 PAID 可发货。 */
    @Transactional
    public OrderDTO ship(String orderNo) {
        Order order = requiredAdminOrder(orderNo);
        assertTransition(order, OrderStatus.SHIPPED);
        return transition(order, OrderStatus.SHIPPED);
    }

    public Page<OrderDTO> list(Long userId, long page, long size) {
        Page<Order> result = orderMapper.selectPage(Page.of(page, size),
                new QueryWrapper<Order>().eq("user_id", userId).orderByDesc("created_at"));
        return toPageDto(result);
    }

    public OrderDTO detail(Long userId, String orderNo) {
        Order order = requiredOrder(userId, orderNo);
        return toDto(order, loadItems(order.getId()));
    }

    // ---------- 定时任务：超时自动确认收货 ----------

    /**
     * 将发货超过 N 天仍未确认收货的订单批量置为 COMPLETED（对齐电商平台的自动确认收货）。
     * 单实例部署无需分布式锁；与用户手动确认收货并发时双方都写 COMPLETED，结果一致幂等。
     *
     * @return 本轮实际完成的订单数
     */
    @Transactional
    public int autoCompleteExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(autoCompleteDays);
        List<Order> expired = orderMapper.selectList(new QueryWrapper<Order>()
                .eq("status", OrderStatus.SHIPPED.name())
                .lt("updated_at", deadline)
                .last("LIMIT " + AUTO_COMPLETE_BATCH));
        int completed = 0;
        for (Order order : expired) {
            // 条件更新：与手动确认收货并发时只有一方写成功；双方目标态一致，结果幂等
            int updated = orderMapper.update(null, new UpdateWrapper<Order>()
                    .eq("id", order.getId())
                    .eq("status", OrderStatus.SHIPPED.name())
                    .set("status", OrderStatus.COMPLETED.name())
                    .set("updated_at", LocalDateTime.now()));
            if (updated == 1) {
                log.info("Auto-completed expired shipped order: orderNo={}", order.getOrderNo());
                completed++;
            } else {
                log.warn("Auto-complete skipped (state changed concurrently): orderNo={}", order.getOrderNo());
            }
        }
        return completed;
    }

    /** 服务间内部查询：订单号是否存在及当前状态（供商品服务对账孤儿扣减时判断是否需要回补） */
    public InternalOrderState stateByOrderNo(String orderNo) {
        Order order = orderMapper.selectOne(new QueryWrapper<Order>().eq("order_no", orderNo).last("LIMIT 1"));
        return order == null ? InternalOrderState.missing() : new InternalOrderState(true, order.getStatus());
    }

    // ---------- 定时任务：超时自动关单 ----------

    /**
     * 将下单超过 N 分钟仍未支付的订单批量置为 CANCELLED 并回补库存（对齐电商平台的超时关单）。
     * 与手动取消遵守同一 CAS 纪律：先条件更新抢到终态、赢了才回补，输了（并发 pay/取消抢先）零动作——
     * CAS 失败方执行任何库存动作都可能造成"已支付订单 + 凭空多出的库存"。
     * 方法刻意不加事务：逐条 CAS 单语句独立提交，Feign 回补期间不持有数据库连接（与下单链路短事务原则一致）。
     *
     * @return 本轮实际关闭的订单数
     */
    public int autoCloseExpiredPendingOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(autoCloseMinutes);
        List<Order> expired = orderMapper.selectList(new QueryWrapper<Order>()
                .eq("status", OrderStatus.PENDING.name())
                .lt("created_at", deadline)
                .last("LIMIT " + AUTO_COMPLETE_BATCH));
        int closed = 0;
        for (Order order : expired) {
            int updated = orderMapper.update(null, new UpdateWrapper<Order>()
                    .eq("id", order.getId())
                    .eq("status", OrderStatus.PENDING.name())
                    .set("status", OrderStatus.CANCELLED.name())
                    .set("updated_at", LocalDateTime.now()));
            if (updated == 1) {
                log.info("Auto-closed expired pending order: orderNo={}", order.getOrderNo());
                restoreStockQuietly(order);
                closed++;
            } else {
                // 状态已被并发 pay/取消改走：CAS 输家不得触碰库存
                log.warn("Auto-close skipped (state changed concurrently): orderNo={}", order.getOrderNo());
            }
        }
        return closed;
    }

    // ---------- B 端管理查询 ----------

    public Page<OrderDTO> adminList(long page, long size, String status, String keyword) {
        QueryWrapper<Order> query = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            if (validStatuses().noneMatch(s -> s.equals(status))) {
                throw new BizException(ResultCode.BAD_REQUEST.getCode(), "非法订单状态：" + status);
            }
            query.eq("status", status);
        }
        if (StringUtils.hasText(keyword)) {
            query.like("order_no", keyword.trim());
        }
        query.orderByDesc("created_at");
        return toPageDto(orderMapper.selectPage(Page.of(page, size), query));
    }

    public OrderDTO adminDetail(String orderNo) {
        Order order = requiredAdminOrder(orderNo);
        return toDto(order, loadItems(order.getId()));
    }

    public Map<String, Object> orderStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = LocalDate.now().minusDays(6).atStartOfDay();
        Map<String, Object> today = orEmpty(orderMapper.sumSince(todayStart));
        Map<String, Object> all = orEmpty(orderMapper.sumAll());
        List<TrendPoint> trend = buildTrend(orderMapper.trendSince(weekStart), weekStart.toLocalDate());
        return Map.of(
                "todayCount", countOf(today),
                "todayGmv", gmvOf(today),
                "totalCount", countOf(all),
                "totalGmv", gmvOf(all),
                "pendingShipCount", orderMapper.countPendingShip(),
                "trend", trend);
    }

    private java.util.stream.Stream<String> validStatuses() {
        return java.util.Arrays.stream(OrderStatus.values()).map(Enum::name);
    }

    private List<TrendPoint> buildTrend(List<Map<String, Object>> rows, LocalDate startDate) {
        Map<String, Map<String, Object>> byDate = new java.util.HashMap<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String date = String.valueOf(row.get("date"));
            byDate.put(date, row);
        }
        List<TrendPoint> trend = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate day = startDate.plusDays(i);
            Map<String, Object> row = byDate.get(day.format(TREND_DATE));
            trend.add(new TrendPoint(day.format(TREND_DATE),
                    row == null ? 0L : countOf(row),
                    row == null ? BigDecimal.ZERO : gmvOf(row)));
        }
        return trend;
    }

    private Map<String, Object> orEmpty(Map<String, Object> aggregate) {
        return aggregate == null ? Map.of() : aggregate;
    }

    private long countOf(Map<String, Object> aggregate) {
        Object value = aggregate == null ? null : aggregate.get("cnt");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal gmvOf(Map<String, Object> aggregate) {
        Object value = aggregate == null ? null : aggregate.get("gmv");
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        } catch (NumberFormatException parseFailure) {
            log.warn("Failed to parse GMV aggregate: {}", value);
            return BigDecimal.ZERO;
        }
    }

    // ---------- 状态机 ----------

    /**
     * 合法流转：PENDING→PAID/CANCELLED；PAID→SHIPPED；SHIPPED→COMPLETED。
     * 目标状态不合法时抛 ORDER_STATE_ERROR（HTTP 409）。
     */
    private void assertTransition(Order order, OrderStatus target) {
        boolean legal = switch (target) {
            case PAID, CANCELLED -> OrderStatus.PENDING.name().equals(order.getStatus());
            case SHIPPED -> OrderStatus.PAID.name().equals(order.getStatus());
            case COMPLETED -> OrderStatus.SHIPPED.name().equals(order.getStatus());
            default -> false;
        };
        if (!legal) {
            throw new BizException(ResultCode.ORDER_STATE_ERROR);
        }
    }

    /**
     * 状态流转统一入口（cancel/pay/ship/complete）：条件更新（CAS）保证并发安全。
     * 仅当库中当前状态仍等于读取时的状态才写入；影响行数为 0 说明状态已被并发修改
     * （如支付与取消同时发生），抛 ORDER_STATE_ERROR(409)，
     * 杜绝"已取消订单被支付成功 / 已支付订单被取消"这类丢失更新。
     */
    private OrderDTO transition(Order order, OrderStatus target) {
        String from = order.getStatus();
        int updated = orderMapper.update(null, new UpdateWrapper<Order>()
                .eq("id", order.getId())
                .eq("status", from)
                .set("status", target.name())
                .set("updated_at", LocalDateTime.now()));
        if (updated != 1) {
            log.warn("Order state CAS failed (concurrent modification): orderNo={}, from={}, to={}",
                    order.getOrderNo(), from, target);
            throw new BizException(ResultCode.ORDER_STATE_ERROR);
        }
        order.setStatus(target.name());
        return toDto(order);
    }

    private void restoreStockQuietly(Order order) {
        List<OrderItem> items = loadItemEntities(order.getId());
        if (items.isEmpty()) {
            // 没有订单项时无法定位回补商品；对账任务可直接按 dedup 记录的 productId/count 兜底
            log.error("Order has no items, stock restore will rely on reconcile job: orderNo={}", order.getOrderNo());
            return;
        }
        Long productId = items.get(0).getProductId();
        int count = items.stream().mapToInt(OrderItem::getCount).sum();
        try {
            Result<Boolean> result = productClient.restoreStock(productId, Map.of("count", count, "orderNo", order.getOrderNo()));
            if (result != null && result.code() == 0 && Boolean.TRUE.equals(result.data())) {
                log.info("Stock restored on cancel: orderNo={}, productId={}, count={}", order.getOrderNo(), productId, count);
            } else {
                // false = 幂等空操作（扣减记录不存在或已恢复过），不阻断取消
                log.warn("Stock restore was a no-op during cancel: orderNo={}, productId={}", order.getOrderNo(), productId);
            }
        } catch (FeignException exception) {
            // 订单已是 CANCELLED 终态：回补欠账由对账任务按「订单已取消」兜底，这里只告警
            log.error("Stock restore failed during cancel, reconcile job will retry: orderNo={}, productId={}",
                    order.getOrderNo(), productId, exception);
        }
    }

    // ---------- 下单内部流程 ----------

    private void deductStockOrThrow(Long productId, int count, String orderNo) {
        try {
            Result<Boolean> result = productClient.decreaseStock(productId,
                    Map.of("count", count, "orderNo", orderNo));
            if (result == null || result.code() != 0 || !Boolean.TRUE.equals(result.data())) {
                throw new BizException(ResultCode.CONFLICT.getCode(), "库存扣减失败");
            }
        } catch (BizException exception) {
            throw exception;
        } catch (FeignException exception) {
            if (exception.status() == HttpStatus.CONFLICT.value()) {
                // 商品服务明确返回 409：区分「库存不足(40001)」与其它业务冲突
                throw translateStockConflict(exception);
            }
            // 超时等不确定结果：查询扣减状态，已扣减则继续建单，否则失败
            if (!handleFeignUncertainty(productId, count, orderNo)) {
                throw new BizException(ResultCode.SYSTEM_ERROR.getCode(), "库存服务暂不可用");
            }
        }
    }

    private OrderDTO persistOrder(Long userId, ProductDTO product, AddressDTO address,
                                  CreateOrderRequest request, String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalAmount(product.price().multiply(BigDecimal.valueOf(request.count())));
        order.setStatus(OrderStatus.PENDING.name());
        order.setAddressId(address.id());
        order.setReceiverName(address.receiver());
        order.setReceiverPhone(address.phone());
        order.setReceiverAddress(address.fullAddress());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.id());
        item.setProductName(product.name());
        item.setPrice(product.price());
        item.setCount(request.count());
        // 图片随下单固化成快照：商品后续改图/删图不影响历史订单展示
        item.setImageUrl(product.imageUrl());
        orderItemMapper.insert(item);
        return toDto(order, List.of(toItemDto(item)));
    }

    private AddressDTO loadAddress(Long userId, Long addressId) {
        try {
            Result<AddressDTO> response = userClient.getAddress(userId, addressId);
            if (response == null || response.code() != 0 || response.data() == null) {
                throw new BizException(ResultCode.BAD_REQUEST.getCode(), "收货地址不存在");
            }
            return response.data();
        } catch (BizException exception) {
            throw exception;
        } catch (FeignException.NotFound exception) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "收货地址不存在");
        } catch (FeignException exception) {
            log.error("Load address failed: userId={}, addressId={}", userId, addressId, exception);
            throw new BizException(ResultCode.SYSTEM_ERROR.getCode(), "地址服务暂不可用");
        }
    }

    private void compensateQuietly(Long productId, int count, String orderNo) {
        try {
            productClient.restoreStock(productId, Map.of("count", count, "orderNo", orderNo));
        } catch (RuntimeException restoreFailure) {
            log.error("Stock compensation failed, manual intervention required: orderNo={}, productId={}",
                    orderNo, productId, restoreFailure);
        }
    }

    private BizException translateStockConflict(FeignException conflict) {
        try {
            JsonNode body = objectMapper.readTree(conflict.contentUTF8());
            int code = body.path("code").asInt();
            String message = body.path("message").asText("");
            if (code == ResultCode.INSUFFICIENT_STOCK.getCode()) {
                return new BizException(ResultCode.INSUFFICIENT_STOCK);
            }
            return new BizException(ResultCode.CONFLICT.getCode(),
                    message.isBlank() ? "库存扣减失败" : message);
        } catch (Exception parseFailure) {
            log.warn("Failed to parse stock conflict response", parseFailure);
            return new BizException(ResultCode.CONFLICT.getCode(), "库存扣减失败");
        }
    }

    private ProductDTO loadProduct(Long productId) {
        try {
            Result<ProductDTO> response = productClient.getProduct(productId);
            if (response == null || response.code() != 0 || response.data() == null) {
                throw new BizException(ResultCode.NOT_FOUND);
            }
            return response.data();
        } catch (FeignException.NotFound exception) {
            throw new BizException(ResultCode.NOT_FOUND);
        } catch (FeignException exception) {
            throw new BizException(ResultCode.SYSTEM_ERROR.getCode(), "商品服务暂不可用");
        }
    }

    private Order requiredOrder(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(new QueryWrapper<Order>().eq("order_no", orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return order;
    }

    private Order requiredAdminOrder(String orderNo) {
        Order order = orderMapper.selectOne(new QueryWrapper<Order>().eq("order_no", orderNo));
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return order;
    }

    private Page<OrderDTO> toPageDto(Page<Order> result) {
        Page<OrderDTO> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(result.getRecords().stream().map(this::toDto).toList());
        return mapped;
    }

    private List<OrderItem> loadItemEntities(Long orderId) {
        return orderItemMapper.selectList(new QueryWrapper<OrderItem>().eq("order_id", orderId));
    }

    private List<OrderItemDTO> loadItems(Long orderId) {
        List<OrderItem> items = loadItemEntities(orderId);
        backfillMissingImages(items);
        return items.stream().map(this::toItemDto).toList();
    }

    /**
     * 存量订单项没有图片快照（v1.3 之前下单的数据）：
     * 展示时按 productId 向商品服务 best-effort 回填当前图片，
     * 失败仅记日志、返回 null 由前端兜底占位图，不阻断详情查询。
     */
    private void backfillMissingImages(List<OrderItem> items) {
        Set<Long> missing = items.stream()
                .filter(item -> item.getImageUrl() == null || item.getImageUrl().isBlank())
                .map(OrderItem::getProductId)
                .collect(Collectors.toSet());
        for (Long productId : missing) {
            try {
                Result<ProductDTO> response = productClient.getProduct(productId);
                if (response == null || response.code() != 0 || response.data() == null) {
                    continue;
                }
                String imageUrl = response.data().imageUrl();
                items.stream()
                        .filter(item -> item.getProductId().equals(productId)
                                && (item.getImageUrl() == null || item.getImageUrl().isBlank()))
                        .forEach(item -> item.setImageUrl(imageUrl));
            } catch (Exception lookupFailure) {
                log.warn("Backfill order item image failed: productId={}", productId, lookupFailure);
            }
        }
    }

    private OrderItemDTO toItemDto(OrderItem item) {
        return new OrderItemDTO(item.getProductId(), item.getProductName(), item.getPrice(),
                item.getCount(), item.getImageUrl());
    }

    private OrderDTO toDto(Order order) {
        return toDto(order, null);
    }

    private OrderDTO toDto(Order order, List<OrderItemDTO> items) {
        return new OrderDTO(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                order.getAddressId(), order.getReceiverName(), order.getReceiverPhone(),
                order.getReceiverAddress(), order.getUserId(), items, order.getCreatedAt());
    }

    /** Feign 失败后的不确定结果处理：已扣减返回 true（继续建单）；未扣减或已补偿返回 false。 */
    private boolean handleFeignUncertainty(Long productId, int count, String orderNo) {
        try {
            Result<String> status = productClient.stockStatus(orderNo);
            return status != null && status.code() == 0 && "DEDUCTED".equals(status.data());
        } catch (FeignException queryException) {
            try {
                productClient.restoreStock(productId, Map.of("count", count, "orderNo", orderNo));
            } catch (FeignException restoreException) {
                log.error("Stock restore after Feign failure failed: orderNo={}, productId={}", orderNo, productId, restoreException);
            }
            return false;
        }
    }
}
