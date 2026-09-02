package com.mall.product.integration;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.mall.common.result.Result;
import com.mall.product.entity.Product;
import com.mall.product.entity.StockDedupLog;
import com.mall.product.feign.OrderClient;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.StockDedupLogMapper;
import com.mall.product.service.ProductService;
import com.mall.product.task.OrphanDeductionReconcileJob;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存一致性集成测试：真实 MySQL + Redis（Testcontainers），纯 Mockito 单测无法覆盖的
 * 并发竞态与事务/幂等在真实数据库语义下的行为都在这里验证。
 * 用例直接对应 docs/CODE_REVIEW.md 的两个 P0 修复（同单号并发扣减、并发回补）与对账闭环。
 * 本地无 Docker 时自动跳过（disabledWithoutDocker）；CI 的 ubuntu runner 自带 Docker 会真实执行。
 */
@SpringBootTest(properties = {
        // 单服务集成测试：不起注册中心，订单服务用 mock 替身
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "mall.jwt.secret=integration-test-secret-0123456789abcdef",
        // 对账任务不随调度自动运行，由用例手动调用 reconcileOnce()
        "mall.product.reconcile-initial-delay-ms=3600000",
        "mall.product.reconcile-interval-ms=3600000"
})
@Testcontainers(disabledWithoutDocker = true)
class StockConsistencyIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("mall_product")
            // 只挂 product 库自包含的 schema（含 dedup 表与唯一索引）。
            // zz-migration-* 脚本假设 mall_user/mall_order 已存在（USE 无 CREATE），
            // 单库测试容器挂载会因库不存在而 init 失败（容器 exit 1）。
            .withCopyFileToContainer(MountableFile.forHostPath("../docs/sql/product-schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Autowired
    ProductService productService;
    @Autowired
    ProductMapper productMapper;
    @Autowired
    StockDedupLogMapper dedupLogMapper;
    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    OrphanDeductionReconcileJob reconcileJob;
    @MockitoBean
    OrderClient orderClient;

    private long newProduct(int stock) {
        Product product = new Product();
        product.setName("集成测试商品");
        product.setPrice(BigDecimal.TEN);
        product.setStock(stock);
        product.setCategoryId(1L);
        product.setStatus(1);
        product.setVersion(0);
        productMapper.insert(product);
        return product.getId();
    }

    private int stockOf(long productId) {
        return productMapper.selectById(productId).getStock();
    }

    /** 并发发令枪：所有线程在同一屏障后同时发起，最大化真实竞态窗口 */
    private List<Boolean> race(int threads, java.util.function.Supplier<Boolean> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.get();
                }));
            }
            ready.await();
            go.countDown();
            return futures.stream().map(f -> {
                try {
                    return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDecreaseWithSameOrderNoDeductsExactlyOnce() throws Exception {
        // 回归 docs/CODE_REVIEW.md P0-1：同 orderNo 并发重试撞幂等表唯一键时必须零净效果，
        // 旧实现（先扣库存后插幂等表 + 吞 DuplicateKey）会把同一单号扣两次
        long productId = newProduct(1000);

        List<Boolean> results = race(16, () -> productService.decreaseStock(productId, 1, "ORD-CONC-1"));

        assertTrue(results.stream().allMatch(Boolean::booleanValue), "同单号所有请求都应幂等成功");
        assertEquals(999, stockOf(productId), "同单号并发 16 次只允许扣减 1 件");
        List<StockDedupLog> logs = dedupLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockDedupLog>()
                        .eq("order_no", "ORD-CONC-1"));
        assertEquals(1, logs.size(), "幂等表只应有一条 DEDUCTED 记录");
    }

    @Test
    void concurrentRestoreIsIdempotent() throws Exception {
        // 回归 P0 修复的回补闸门：并发双回补靠 DEDUCTED→RESTORED 条件翻转抢占，只允许回补一次
        long productId = newProduct(1000);
        assertTrue(productService.decreaseStock(productId, 2, "ORD-RESTORE-1"));
        assertEquals(998, stockOf(productId));

        List<Boolean> results = race(8, () -> productService.restoreStock(productId, 2, "ORD-RESTORE-1"));

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count(), "并发回补只应有一个赢家");
        assertEquals(1000, stockOf(productId), "库存只回补一次，不多不少");
        assertEquals("RESTORED", dedupLogMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StockDedupLog>()
                        .eq("order_no", "ORD-RESTORE-1")).getStatus());
    }

    @Test
    void reconcileRestoresOrphanButSparesLiveOrder() {
        // 对账闭环：订单不存在（孤儿）→ 回补；订单存在且 PENDING（正常持有）→ 不动
        long productId = newProduct(500);

        // 孤儿：扣减后订单永远没建出来
        assertTrue(productService.decreaseStock(productId, 3, "ORD-IT-ORPHAN"));
        dedupLogMapper.update(null, new UpdateWrapper<StockDedupLog>()
                .eq("order_no", "ORD-IT-ORPHAN")
                .set("created_at", LocalDateTime.now().minusMinutes(30)));
        Mockito.when(orderClient.orderState("ORD-IT-ORPHAN"))
                .thenReturn(Result.ok(new OrderClient.OrderState(false, null)));

        assertEquals(1, reconcileJob.reconcileOnce());
        assertEquals(500, stockOf(productId));

        // 在途正常订单：dedup 已过期但订单存在且 PENDING，绝不能回补
        assertTrue(productService.decreaseStock(productId, 1, "ORD-IT-LIVE"));
        dedupLogMapper.update(null, new UpdateWrapper<StockDedupLog>()
                .eq("order_no", "ORD-IT-LIVE")
                .set("created_at", LocalDateTime.now().minusMinutes(30)));
        Mockito.when(orderClient.orderState("ORD-IT-LIVE"))
                .thenReturn(Result.ok(new OrderClient.OrderState(true, "PENDING")));

        assertEquals(0, reconcileJob.reconcileOnce());
        assertEquals(499, stockOf(productId), "正常持有中的订单库存不得被对账回补");
    }

    @Test
    void cacheEvictedAfterCommitAndRefilledWithFreshStock() {
        // 库存路径的缓存失效必须在事务提交后发生：提交前删除会被并发读用旧值回填
        long productId = newProduct(100);
        productService.detail(productId);
        String cacheKey = "product:detail:" + productId;
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey)), "detail() 应预热缓存");

        assertTrue(productService.decreaseStock(productId, 1, "ORD-IT-CACHE"));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey)), "扣减提交后缓存应被清除");

        assertEquals(99, productService.detail(productId).stock(), "重新回源的缓存必须是扣减后的新值");
    }
}
