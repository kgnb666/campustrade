package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 生成商品描述请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiDescriptionDTO {

    /**
     * 商品标题 (最大 100 字符)
     */
    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题长度不能超过100字符")
    private String title;

    /**
     * 商品成色 (如: 全新/99新/95新/9成新/8成新以下)
     */
    @Size(max = 20, message = "成色描述长度不能超过20字符")
    private String conditionLevel;

    /**
     * 用户输入的简要描述或原描述 (最大 1000 字符)
     */
    @Size(max = 1000, message = "原描述长度不能超过1000字符")
    private String originalDescription;

    /**
     * 交易自提地点
     */
    @Size(max = 100, message = "自提地点长度不能超过100字符")
    private String location;
}
