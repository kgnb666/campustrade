package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品分类树形视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryVO {

    private Long id;

    private Long parentId;

    private String name;

    private String icon;

    private Integer sort;

    @Builder.Default
    private List<CategoryVO> children = new ArrayList<>();
}
