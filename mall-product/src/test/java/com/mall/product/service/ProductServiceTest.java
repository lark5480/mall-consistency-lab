package com.mall.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.dto.ProductRequest;
import com.mall.product.entity.Product;
import com.mall.product.entity.StockDedupLog;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.StockDedupLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

class ProductServiceTest {
    private ProductMapper productMapper;
    private StockDedupLogMapper dedupLogMapper;
    private StringRedisTemplate redisTemplate;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productMapper = Mockito.mock(ProductMapper.class);
        dedupLogMapper = Mockito.mock(StockDedupLogMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        productService = new ProductService(productMapper, dedupLogMapper, redisTemplate,
                new ObjectMapper(), 10L);
    }

    private Product product(long id, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName("Phone");
        product.setPrice(BigDecimal.TEN);
        product.setStock(stock);
        product.setCategoryId(1L);
        product.setStatus(1);
        product.setVersion(0);
        return product;
    }

    @Test
    void decreaseStockShouldBeIdempotentPerOrderNo() {
        StockDedupLog existing = new StockDedupLog();
        existing.setOrderNo("ORD1");
        existing.setProductId(1L);
        existing.setCount(2);
        existing.setStatus("DEDUCTED");
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(existing);

        assertTrue(productService.decreaseStock(1L, 2, "ORD1"));
        Mockito.verify(productMapper, Mockito.never()).selectById(Mockito.anyLong());
        Mockito.verify(dedupLogMapper, Mockito.never()).insert(Mockito.any(StockDedupLog.class));
    }

    @Test
    void decreaseStockAfterRestoreShouldReportFalse() {
        StockDedupLog restored = new StockDedupLog();
        restored.setOrderNo("ORD2");
        restored.setStatus("RESTORED");
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(restored);

        assertFalse(productService.decreaseStock(1L, 2, "ORD2"));
    }

    @Test
    void decreaseStockInsufficientShouldThrow40001() {
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(null);
        Mockito.when(productMapper.selectById(2L)).thenReturn(product(2L, 1));

        BizException exception = assertThrows(BizException.class,
                () -> productService.decreaseStock(2L, 5, "ORD3"));
        assertEquals(ResultCode.INSUFFICIENT_STOCK.getCode(), exception.getCode());
        // 幂等占位先行：插入已尝试，抛错后靠事务回滚撤销占位，不留孤儿记录
        Mockito.verify(dedupLogMapper).insert(Mockito.any(StockDedupLog.class));
    }

    @Test
    void decreaseStockSuccessWritesDedupLogAndDecreasesStock() {
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(null);
        AtomicReference<StockDedupLog> saved = new AtomicReference<>();
        AtomicReference<Product> updated = new AtomicReference<>();
        Mockito.when(productMapper.selectById(1L)).thenReturn(product(1L, 100));
        Mockito.when(productMapper.updateById(Mockito.any(Product.class))).thenAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        });
        Mockito.when(dedupLogMapper.insert(Mockito.any(StockDedupLog.class))).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        });

        assertTrue(productService.decreaseStock(1L, 3, "ORD4"));
        assertEquals(97, updated.get().getStock());
        assertEquals("DEDUCTED", saved.get().getStatus());
        assertEquals(3, saved.get().getCount());
        // 顺序锁定：先插幂等占位、后扣库存（防同单号并发双重扣减的关键）
        InOrder inOrder = Mockito.inOrder(dedupLogMapper, productMapper);
        inOrder.verify(dedupLogMapper).insert(Mockito.any(StockDedupLog.class));
        inOrder.verify(productMapper).updateById(Mockito.any(Product.class));
    }

    @Test
    void decreaseStockDuplicateDedupShouldNotDoubleDeduct() {
        // 回归：同 orderNo 并发重试撞唯一键时必须零净效果，
        // 旧实现先扣库存后插幂等表、吞掉冲突 → 同单号被扣两次库存
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(null);
        Mockito.when(dedupLogMapper.insert(Mockito.any(StockDedupLog.class)))
                .thenThrow(new DuplicateKeyException("uk_dedup_order_no"));

        assertTrue(productService.decreaseStock(1L, 2, "ORD-DUP"));
        Mockito.verify(productMapper, Mockito.never()).selectById(Mockito.anyLong());
        Mockito.verify(productMapper, Mockito.never()).updateById(Mockito.any(Product.class));
    }

    @Test
    void restoreStockShouldRestoreOnceThenIgnore() {
        StockDedupLog log = new StockDedupLog();
        log.setOrderNo("ORD5");
        log.setProductId(1L);
        log.setCount(4);
        log.setStatus("DEDUCTED");
        Mockito.when(dedupLogMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(1);
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(log);
        AtomicReference<Product> restored = new AtomicReference<>();
        Mockito.when(productMapper.selectById(1L)).thenReturn(product(1L, 96));
        Mockito.when(productMapper.updateById(Mockito.any(Product.class))).thenAnswer(invocation -> {
            restored.set(invocation.getArgument(0));
            return 1;
        });

        assertTrue(productService.restoreStock(1L, 4, "ORD5"));
        // 回补量取落库记录值：96 + 记录 count 4 = 100
        assertEquals(100, restored.get().getStock());
        // 回补权经条件翻转（DEDUCTED→RESTORED）抢占，不再走实体盲写
        Mockito.verify(dedupLogMapper).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(dedupLogMapper, Mockito.never()).updateById(Mockito.any(StockDedupLog.class));

        // 第二次回补：翻转影响 0 行（已 RESTORED），幂等跳过、不再动库存
        Mockito.reset(productMapper);
        Mockito.when(dedupLogMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(0);
        assertFalse(productService.restoreStock(1L, 4, "ORD5"));
        Mockito.verify(productMapper, Mockito.never()).selectById(Mockito.anyLong());
        Mockito.verify(productMapper, Mockito.never()).updateById(Mockito.any(Product.class));
    }

    @Test
    void restoreStockShouldUseRecordedCountNotCallerArgument() {
        // 防入参写歪库存：落库记录 count=2，调用方误传 999 也只回补 2
        StockDedupLog log = new StockDedupLog();
        log.setOrderNo("ORD6");
        log.setProductId(1L);
        log.setCount(2);
        log.setStatus("DEDUCTED");
        Mockito.when(dedupLogMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(1);
        Mockito.when(dedupLogMapper.selectOne(any())).thenReturn(log);
        Mockito.when(productMapper.selectById(1L)).thenReturn(product(1L, 98));
        AtomicReference<Product> restored = new AtomicReference<>();
        Mockito.when(productMapper.updateById(Mockito.any(Product.class))).thenAnswer(invocation -> {
            restored.set(invocation.getArgument(0));
            return 1;
        });

        assertTrue(productService.restoreStock(1L, 999, "ORD6"));
        assertEquals(100, restored.get().getStock());
    }

    @Test
    void updateOptimisticConflictShouldThrow409() {
        Mockito.when(productMapper.selectById(1L)).thenReturn(product(1L, 10));
        Mockito.when(productMapper.updateById(Mockito.any(Product.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> productService.update(1L, new ProductRequest(
                        "Phone", null, BigDecimal.TEN, 10, 1L, null, "ON_SALE")));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
    }
}
