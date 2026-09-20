package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 商品描述生成返回 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDescriptionVO {

    /**
     * AI 生成/润色后的描述
     */
    private String generatedDescription;

    /**
     * 提取或推荐的商品标签
     */
    private List<String> tags;

    /**
     * 是否触发优雅降级
     */
    private Boolean degraded;
}
