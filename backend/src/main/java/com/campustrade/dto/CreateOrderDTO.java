package com.campustrade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建交易订单入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderDTO implements Serializable {

    /**
     * 目标商品ID
     */
    @NotNull(message = "商品ID不能为空")
    private Long goodsId;

    /**
     * 线下自提/面交地点 (可选，默认使用商品发布地点)
     */
    @Size(max = 200, message = "面交地点不能超过200个字符")
    private String meetLocation;

    /**
     * 买家留言 (可选)
     */
    @Size(max = 500, message = "买家留言不能超过500个字符")
    private String buyerMessage;
}
