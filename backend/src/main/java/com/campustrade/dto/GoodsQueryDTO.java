package com.campustrade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品分页搜索筛选 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoodsQueryDTO {

    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer size = 10;

    /**
     * 关键词搜索 (标题/描述)
     */
    private String keyword;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 学校 ID
     */
    private Long schoolId;

    /**
     * 最低价格
     */
    private BigDecimal minPrice;

    /**
     * 最高价格
     */
    private BigDecimal maxPrice;

    /**
     * 成色筛选
     */
    private String conditionLevel;
}
