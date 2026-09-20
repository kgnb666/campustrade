package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 智能分类推荐请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCategoryDTO {

    /**
     * 商品标题 (最大 100 字符)
     */
    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题长度不能超过100字符")
    private String title;

    /**
     * 商品详细描述 (最大 1000 字符)
     */
    @Size(max = 1000, message = "商品描述长度不能超过1000字符")
    private String description;
}
