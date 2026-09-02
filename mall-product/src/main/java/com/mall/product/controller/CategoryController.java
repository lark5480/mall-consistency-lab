package com.mall.product.controller;

import com.mall.common.result.Result;
import com.mall.product.dto.CategoryDTO;
import com.mall.product.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 公开接口：C 端分类筛选与 B 端管理共用，无需登录。 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<CategoryDTO>> list() {
        return Result.ok(categoryService.list());
    }
}
