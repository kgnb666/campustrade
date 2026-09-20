package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * AI 二手定价建议请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPriceDTO {

    /**
     * 商品标题 (最大 100 字符)
     */
    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题长度不能超过100字符")
    private String title;

    /**
     * 成色级别
     */
    @Size(max = 20, message = "成色描述长度不能超过20字符")
    private String conditionLevel;

    /**
     * 官方原价或购入原价 (可选)
     */
    private BigDecimal originalPrice;

    /**
     * 分类名称 (可选)
     */
    @Size(max = 50, message = "分类名称长度不能超过50字符")
    private String categoryName;
}
