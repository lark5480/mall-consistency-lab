package com.mall.product.controller;

import com.mall.common.result.Result;
import com.mall.product.dto.CategoryDTO;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端分类管理。网关对 /api/v1/admin/** 强制 ADMIN 角色。
 */
@RestController
@RequestMapping("/api/v1/admin/categories")
public class AdminCategoryController {
    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public Result<CategoryDTO> create(@Valid @RequestBody CategorySaveRequest request) {
        return Result.ok(categoryService.create(request));
    }

    @PutMapping("/{categoryId}")
    public Result<CategoryDTO> update(@PathVariable Long categoryId,
                                      @Valid @RequestBody CategorySaveRequest request) {
        return Result.ok(categoryService.update(categoryId, request));
    }

    @DeleteMapping("/{categoryId}")
    public Result<Void> delete(@PathVariable Long categoryId) {
        categoryService.delete(categoryId);
        return Result.ok();
    }
}
