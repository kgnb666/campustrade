package com.campustrade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布商品请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGoodsDTO {

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题不能超过100个字符")
    private String title;

    private String description;

    @NotNull(message = "请选择商品分类")
    private Long categoryId;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    private BigDecimal price;

    private BigDecimal originalPrice;

    @NotBlank(message = "请选择商品成色")
    private String conditionLevel;

    private String location;

    /**
     * 商品图片 URL 列表
     */
    @Builder.Default
    private List<String> images = new ArrayList<>();

    /**
     * 商品标签列表
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
