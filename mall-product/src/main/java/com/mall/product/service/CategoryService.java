package com.mall.product.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.dto.CategoryDTO;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.entity.Category;
import com.mall.product.entity.Product;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService {
    /** 低库存阈值：B 端看板「库存预警」口径，与 PRD 演示商品「限量卫衣 stock=5」呼应。 */
    static final int LOW_STOCK_THRESHOLD = 10;

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;

    public CategoryService(CategoryMapper categoryMapper, ProductMapper productMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
    }

    public List<CategoryDTO> list() {
        return categoryMapper.selectList(new QueryWrapper<Category>().orderByAsc("id"))
                .stream().map(this::toDto).toList();
    }

    public CategoryDTO create(CategorySaveRequest request) {
        Category category = new Category();
        applyName(category, request);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "分类名已存在");
        }
        return toDto(category);
    }

    public CategoryDTO update(Long categoryId, CategorySaveRequest request) {
        Category category = requiredCategory(categoryId);
        applyName(category, request);
        category.setUpdatedAt(LocalDateTime.now());
        try {
            categoryMapper.updateById(category);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ResultCode.CONFLICT.getCode(), "分类名已存在");
        }
        return toDto(category);
    }

    public void delete(Long categoryId) {
        requiredCategory(categoryId);
        long referenced = productMapper.selectCount(
                new QueryWrapper<Product>().eq("category_id", categoryId));
        if (referenced > 0) {
            throw new BizException(ResultCode.CONFLICT.getCode(),
                    "该分类下仍有 " + referenced + " 个商品，请先移除或改分类");
        }
        categoryMapper.deleteById(categoryId);
    }

    public Map<String, Object> productStats() {
        return Map.of(
                "totalCount", productMapper.selectCount(null),
                "onSaleCount", productMapper.selectCount(new QueryWrapper<Product>().eq("status", 1)),
                "offSaleCount", productMapper.selectCount(new QueryWrapper<Product>().eq("status", 0)),
                "lowStockCount", productMapper.selectCount(new QueryWrapper<Product>()
                        .eq("status", 1).lt("stock", LOW_STOCK_THRESHOLD)));
    }

    private Category requiredCategory(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
        return category;
    }

    private void applyName(Category category, CategorySaveRequest request) {
        category.setName(request.name().trim());
    }

    private CategoryDTO toDto(Category category) {
        return new CategoryDTO(category.getId(), category.getName());
    }
}
