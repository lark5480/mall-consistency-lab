package com.mall.product.service;

import com.mall.common.exception.BizException;
import com.mall.common.result.ResultCode;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.entity.Category;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategoryServiceTest {
    private CategoryMapper categoryMapper;
    private ProductMapper productMapper;
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryMapper = Mockito.mock(CategoryMapper.class);
        productMapper = Mockito.mock(ProductMapper.class);
        categoryService = new CategoryService(categoryMapper, productMapper);
    }

    private Category category(long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    @Test
    void deleteShouldRejectWhenProductsStillReferenced() {
        Mockito.when(categoryMapper.selectById(1L)).thenReturn(category(1L, "数码"));
        Mockito.when(productMapper.selectCount(Mockito.any())).thenReturn(3L);

        BizException exception = assertThrows(BizException.class, () -> categoryService.delete(1L));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
        Mockito.verify(categoryMapper, Mockito.never()).deleteById(Mockito.anyLong());
    }

    @Test
    void deleteShouldSucceedWhenNoProductReferenced() {
        Mockito.when(categoryMapper.selectById(2L)).thenReturn(category(2L, "图书"));
        Mockito.when(productMapper.selectCount(Mockito.any())).thenReturn(0L);

        categoryService.delete(2L);

        Mockito.verify(categoryMapper).deleteById(2L);
    }

    @Test
    void createDuplicateNameShouldTranslateTo409() {
        Mockito.when(categoryMapper.insert(Mockito.any(Category.class)))
                .thenThrow(new DuplicateKeyException("uk_category_name"));

        BizException exception = assertThrows(BizException.class,
                () -> categoryService.create(new CategorySaveRequest("数码")));
        assertEquals(ResultCode.CONFLICT.getCode(), exception.getCode());
    }

    @Test
    void productStatsShouldAggregateCounts() {
        Mockito.when(productMapper.selectCount(Mockito.isNull())).thenReturn(10L);
        Mockito.when(productMapper.selectCount(Mockito.argThat(wrapper -> wrapper != null)))
                .thenReturn(7L);

        Map<String, Object> stats = categoryService.productStats();

        assertEquals(10L, stats.get("totalCount"));
    }
}
