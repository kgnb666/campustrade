package com.campustrade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 修改商品请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGoodsDTO {

    @Size(max = 100, message = "商品标题不能超过100个字符")
    private String title;

    private String description;

    private Long categoryId;

    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    private BigDecimal price;

    private BigDecimal originalPrice;

    private String conditionLevel;

    private String location;

    private List<String> images;

    private List<String> tags;
}
