package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.entity.Category;
import com.campustrade.mapper.CategoryMapper;
import com.campustrade.service.CategoryService;
import com.campustrade.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品分类服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    @Override
    public List<CategoryVO> getCategoryTree() {
        // 1. 查询所有启用的分类，按排序值升序排列
        List<Category> allCategories = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, 1)
                        .orderByAsc(Category::getSort)
        );

        if (allCategories == null || allCategories.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 转换为 VO
        List<CategoryVO> voList = allCategories.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        // 3. 按照 parentId 分组
        Map<Long, List<CategoryVO>> childrenMap = voList.stream()
                .filter(item -> item.getParentId() != null && item.getParentId() > 0)
                .collect(Collectors.groupingBy(CategoryVO::getParentId));

        // 4. 将子分类填充至对应父节点
        List<CategoryVO> rootList = voList.stream()
                .filter(item -> item.getParentId() == null || item.getParentId() == 0)
                .peek(root -> root.setChildren(childrenMap.getOrDefault(root.getId(), new ArrayList<>())))
                .collect(Collectors.toList());

        return rootList;
    }

    private CategoryVO convertToVO(Category category) {
        return CategoryVO.builder()
                .id(category.getId())
                .parentId(category.getParentId())
                .name(category.getName())
                .icon(category.getIcon())
                .sort(category.getSort())
                .children(new ArrayList<>())
                .build();
    }
}
