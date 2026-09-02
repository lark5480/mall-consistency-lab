package com.mall.product.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.dto.ProductDTO;
import com.mall.product.dto.ProductRequest;
import com.mall.product.entity.Product;
import com.mall.product.entity.StockDedupLog;
import com.mall.product.mapper.ProductMapper;
import com.mall.product.mapper.StockDedupLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Slf4j
public class ProductService {
    private final ProductMapper productMapper;
    private final StockDedupLogMapper dedupLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public ProductService(ProductMapper productMapper, StockDedupLogMapper dedupLogMapper,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          @Value("${mall.cache.product-ttl-minutes:10}") long ttlMinutes) {
        this.productMapper = productMapper;
        this.dedupLogMapper = dedupLogMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtl = Duration.ofMinutes(ttlMinutes);
    }

    public Page<ProductDTO> list(long page, long size, Long categoryId, String keyword) {
        QueryWrapper<Product> query = new QueryWrapper<Product>()
                .eq(categoryId != null, "category_id", categoryId)
                .like(StringUtils.hasText(keyword), "name", keyword)
                .orderByDesc("created_at");
        Page<Product> result = productMapper.selectPage(Page.of(page, size), query);
        Page<ProductDTO> mapped = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        mapped.setRecords(result.getRecords().stream().map(this::toDto).toList());
        return mapped;
    }

    public ProductDTO detail(Long productId) {
        String key = "product:detail:" + productId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.equals("null")) {
                return objectMapper.readValue(cached, ProductDTO.class);
            }
        } catch (Exception ignored) {
            log.warn("Read product cache failed: productId={}", productId, ignored);
        }
        ProductDTO dto = toDto(requiredProduct(productId));
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(dto), cacheTtl);
        } catch (Exception ignored) {
            log.warn("Write product cache failed: productId={}", productId, ignored);
        }
        return dto;
    }

    public ProductDTO create(ProductRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Product product = new Product();
        applyRequest(product, request);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        product.setVersion(0);
        productMapper.insert(product);
        return toDto(product);
    }

    public ProductDTO update(Long productId, ProductRequest request) {
        Product product = requiredProduct(productId);
        applyRequest(product, request);
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.updateById(product) != 1) {
            log.warn("Product optimistic update conflicted: productId={}", productId);
            throw new BizException(ResultCode.CONFLICT.getCode(), "商品已被修改，请刷新后重试");
        }
        evictCache(productId);
        return toDto(product);
    }

    public void delete(Long productId) {
        requiredProduct(productId);
        if (productMapper.deleteById(productId) != 1) {
            log.warn("Product delete conflicted: productId={}", productId);
            throw new BizException(ResultCode.CONFLICT.getCode(), "商品已被修改或删除，请刷新后重试");
        }
        evictCache(productId);
    }

    /**
     * 幂等扣库存：先插 stock_dedup_log 占住 order_no 唯一键，再扣库存。
     * 顺序不能颠倒——若先扣库存再插幂等表，同 orderNo 的并发重试会在版本冲突后
     * 读到新版本二次扣减成功、随后才撞唯一键，把冲突当幂等成功就是双重扣库存；
     * 先插表则唯一键先行仲裁：抢到占位的一方才允许扣减，插入之后任何失败
     * （库存不足/乐观锁冲突重试耗尽）都整体回滚、占位一并撤销，不留孤儿占位。
     * 撞唯一键意味着已有一次已提交的扣减（占位只随扣减一起提交），返回幂等成功。
     */
    @Transactional
    public boolean decreaseStock(Long productId, int count, String orderNo) {
        StockDedupLog existing = dedupLogMapper.selectOne(
                new QueryWrapper<StockDedupLog>().eq("order_no", orderNo).last("LIMIT 1"));
        if (existing != null) {
            return "DEDUCTED".equals(existing.getStatus());
        }
        StockDedupLog dedup = new StockDedupLog();
        dedup.setOrderNo(orderNo);
        dedup.setProductId(productId);
        dedup.setCount(count);
        dedup.setStatus("DEDUCTED");
        dedup.setCreatedAt(LocalDateTime.now());
        try {
            dedupLogMapper.insert(dedup);
        } catch (DuplicateKeyException duplicate) {
            log.warn("Concurrent dedup insert detected (orderNo={}), treating as idempotent success", orderNo);
            return true;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Product product = productMapper.selectById(productId);
            if (product == null || product.getStock() < count) {
                throw new BizException(ResultCode.INSUFFICIENT_STOCK);
            }
            product.setStock(product.getStock() - count);
            product.setUpdatedAt(LocalDateTime.now());
            if (productMapper.updateById(product) == 1) {
                evictCacheAfterCommit(productId);
                return true;
            }
        }
        throw new BizException(ResultCode.CONFLICT.getCode(), "库存扣减冲突，请稍后重试");
    }

    /**
     * 幂等回补库存：先用条件更新把 dedup 记录 DEDUCTED→RESTORED 抢占回补权，
     * 抢到（影响 1 行）才动库存——该闸门不依赖隔离级别，杜绝并发双回补；
     * 抢占后的任何失败（记录不一致/商品不存在/乐观锁冲突）整体回滚、翻转一并撤销，可安全重试。
     * 回补数量取落库记录值而非调用方入参，防止入参写歪库存。
     */
    @Transactional
    public boolean restoreStock(Long productId, int count, String orderNo) {
        int claimed = dedupLogMapper.update(null, new UpdateWrapper<StockDedupLog>()
                .eq("order_no", orderNo)
                .eq("status", "DEDUCTED")
                .set("status", "RESTORED"));
        if (claimed == 0) {
            return false;
        }
        StockDedupLog dedupLog = dedupLogMapper.selectOne(
                new QueryWrapper<StockDedupLog>().eq("order_no", orderNo).last("LIMIT 1"));
        if (dedupLog == null || !dedupLog.getProductId().equals(productId)) {
            log.error("Stock restore request mismatches deduction record: orderNo={}, requestProductId={}, recordProductId={}",
                    orderNo, productId, dedupLog == null ? null : dedupLog.getProductId());
            throw new BizException(ResultCode.CONFLICT.getCode(), "库存恢复请求与扣减记录不一致");
        }
        Product product = productMapper.selectById(dedupLog.getProductId());
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        product.setStock(product.getStock() + dedupLog.getCount());
        product.setUpdatedAt(LocalDateTime.now());
        if (productMapper.updateById(product) != 1) {
            log.warn("Stock restore optimistic conflict: orderNo={}, productId={}", orderNo, productId);
            throw new BizException(ResultCode.CONFLICT.getCode(), "库存恢复冲突，请重试");
        }
        evictCacheAfterCommit(productId);
        return true;
    }

    public String stockStatus(String orderNo) {
        StockDedupLog dedupLog = dedupLogMapper.selectOne(
                new QueryWrapper<StockDedupLog>().eq("order_no", orderNo).last("LIMIT 1"));
        return dedupLog == null ? "NONE" : dedupLog.getStatus();
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategoryId(request.categoryId());
        product.setImageUrl(request.imageUrl());
        if (request.status() != null) {
            product.setStatus("ON_SALE".equals(request.status()) ? 1 : 0);
        }
    }

    private Product requiredProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return product;
    }

    private void evictCache(Long productId) {
        try {
            redisTemplate.delete("product:detail:" + productId);
        } catch (Exception cacheFailure) {
            log.warn("Evict product cache failed: productId={}", productId, cacheFailure);
        }
    }

    /**
     * 事务内路径（decrease/restore）必须等提交后再删缓存：
     * 提交前删除会被并发读用旧值回填缓存，旧数据最长存活一个 TTL。
     * 无事务上下文时（非事务调用方）退化为立即删除。
     */
    private void evictCacheAfterCommit(Long productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCache(productId);
                }
            });
        } else {
            evictCache(productId);
        }
    }

    private ProductDTO toDto(Product product) {
        return new ProductDTO(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.getCategoryId(), product.getImageUrl(),
                product.getStatus() == null ? "OFF_SALE" : product.getStatus() == 1 ? "ON_SALE" : "OFF_SALE",
                product.getCreatedAt());
    }
}
