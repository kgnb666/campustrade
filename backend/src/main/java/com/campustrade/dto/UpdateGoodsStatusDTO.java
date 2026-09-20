package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品状态修改 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGoodsStatusDTO {

    /**
     * 商品目标状态: ON_SALE, OFF_SHELF 等
     */
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
