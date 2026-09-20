package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 分类推荐返回 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCategoryVO {

    /**
     * 推荐分类 ID
     */
    private Long categoryId;

    /**
     * 推荐分类名称
     */
    private String categoryName;

    /**
     * 推荐置信度 (0.0 ~ 1.0)
     */
    private Double confidence;

    /**
     * 推荐理由说明
     */
    private String reason;

    /**
     * 是否触发优雅降级
     */
    private Boolean degraded;
}
