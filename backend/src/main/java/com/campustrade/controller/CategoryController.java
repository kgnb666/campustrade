package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.service.CategoryService;
import com.campustrade.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类控制器
 */
@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 获取商品多级分类树
     */
    @GetMapping("/list")
    public Result<List<CategoryVO>> getCategoryList() {
        List<CategoryVO> tree = categoryService.getCategoryTree();
        return Result.success("获取分类成功", tree);
    }
}
