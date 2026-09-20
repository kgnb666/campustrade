package com.campustrade.service;

import com.campustrade.vo.CategoryVO;

import java.util.List;

/**
 * 商品分类服务接口
 */
public interface CategoryService {

    /**
     * 获取多级分类树形结构
     */
    List<CategoryVO> getCategoryTree();
}
