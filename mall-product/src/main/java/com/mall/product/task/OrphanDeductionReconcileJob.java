package com.mall.product.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mall.common.result.Result;
import com.mall.product.entity.StockDedupLog;
import com.mall.product.feign.OrderClient;
import com.mall.product.mapper.StockDedupLogMapper;
import com.mall.product.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿扣减对账任务：下单链路是「远程扣库存 → 本地落订单」的 Saga，
 * 会有两类 DEDUCTED 记录需要兜底回补——
 * ① 扣库存后进程崩溃，补偿没机会执行，订单表查无此单号（孤儿）；
 * ② 订单取消时远程回补失败（mall-order 先 CAS 落 CANCELLED 再尽力回补，失败只告警）。
 * 本任务周期扫描超过 staleMinutes 仍处于 DEDUCTED 的去重记录，向订单服务确认单号状态：
 * 订单不存在或已 CANCELLED 才回补；其余状态（PENDING/PAID/...）说明库存被订单正常持有，跳过。
 * 状态不明（查询失败/响应异常）一律跳过——宁可少补一轮等下轮重试，不可误补正常订单的库存。
 * 顺带清理超过保留期的 RESTORED 记录，防止去重表随订单量无限膨胀。
 */
@Component
@Slf4j
public class OrphanDeductionReconcileJob {
    /** 单轮最大处理量 */
    private static final long BATCH_LIMIT = 100;
    /** mall-order OrderStatus 的取消态字面量（服务间 DTO 有意不复用，此处保持字面量一致） */
    private static final String ORDER_CANCELLED = "CANCELLED";

    private final StockDedupLogMapper dedupLogMapper;
    private final ProductService productService;
    private final OrderClient orderClient;
    /** 扣减记录视为"疑似待回补"的最小年龄（分钟） */
    private final long staleMinutes;
    /** RESTORED 记录保留天数，超过后清理 */
    private final long retainRestoredDays;

    public OrphanDeductionReconcileJob(StockDedupLogMapper dedupLogMapper,
                                       ProductService productService,
                                       OrderClient orderClient,
                                       @Value("${mall.product.reconcile-stale-minutes:10}") long staleMinutes,
                                       @Value("${mall.product.reconcile-retain-restored-days:7}") long retainRestoredDays) {
        this.dedupLogMapper = dedupLogMapper;
        this.productService = productService;
        this.orderClient = orderClient;
        this.staleMinutes = staleMinutes;
        this.retainRestoredDays = retainRestoredDays;
    }

    @Scheduled(fixedDelayString = "${mall.product.reconcile-interval-ms:300000}",
            initialDelayString = "${mall.product.reconcile-initial-delay-ms:120000}")
    public void reconcile() {
        try {
            int restored = reconcileOnce();
            int cleaned = cleanupRestoredLogs();
            if (restored > 0 || cleaned > 0) {
                log.info("Orphan deduction reconcile round finished: restored={}, cleanedRestoredLogs={}", restored, cleaned);
            }
        } catch (Exception failure) {
            // 定时任务不允许抛出异常中断调度线程，记录后等下一轮重试
            log.error("Orphan deduction reconcile round failed", failure);
        }
    }

    /** 可测试的核心逻辑：返回本轮回补的记录数。单实例部署无需分布式锁。 */
    public int reconcileOnce() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleMinutes);
        List<StockDedupLog> staleDeductions = dedupLogMapper.selectList(new QueryWrapper<StockDedupLog>()
                .eq("status", "DEDUCTED")
                .lt("created_at", staleBefore)
                .orderByAsc("created_at")
                .last("LIMIT " + BATCH_LIMIT));
        int restored = 0;
        for (StockDedupLog record : staleDeductions) {
            try {
                if (!shouldRestore(record)) {
                    continue;
                }
                // restoreStock 自带回补闸门：条件翻转 DEDUCTED→RESTORED 抢到才回补，天然幂等
                if (productService.restoreStock(record.getProductId(), record.getCount(), record.getOrderNo())) {
                    log.warn("Reconciled stock deduction: orderNo={}, productId={}, count={}",
                            record.getOrderNo(), record.getProductId(), record.getCount());
                    restored++;
                } else {
                    log.warn("Skip deduction already restored during reconcile: orderNo={}", record.getOrderNo());
                }
            } catch (Exception failure) {
                log.error("Reconcile failed for deduction: orderNo={}", record.getOrderNo(), failure);
            }
        }
        return restored;
    }

    /**
     * 回补判据：订单不存在（孤儿）或已取消（取消时回补失败的欠账）。
     * 查询异常或响应体异常（code != 0 / data 缺失）一律视为状态不明，跳过不补——
     * 旧实现把"服务端业务错误"当作订单不存在处理，一旦内部接口未来返回 200+错误码就会误回补。
     */
    private boolean shouldRestore(StockDedupLog record) {
        Result<OrderClient.OrderState> response;
        try {
            response = orderClient.orderState(record.getOrderNo());
        } catch (Exception queryFailure) {
            log.warn("Skip reconcile, order state unknown: orderNo={}", record.getOrderNo(), queryFailure);
            return false;
        }
        if (response == null || response.code() != 0 || response.data() == null) {
            log.warn("Skip reconcile, order state uncertain: orderNo={}", record.getOrderNo());
            return false;
        }
        if (!response.data().exists()) {
            return true;
        }
        return ORDER_CANCELLED.equals(response.data().status());
    }

    private int cleanupRestoredLogs() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retainRestoredDays);
        return dedupLogMapper.delete(new QueryWrapper<StockDedupLog>()
                .eq("status", "RESTORED")
                .lt("created_at", threshold));
    }
}
