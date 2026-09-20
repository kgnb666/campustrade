package com.campustrade.dto.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建订单请求入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest implements Serializable {

    /**
     * 目标商品ID (必填)
     */
    @NotNull(message = "商品ID不能为空")
    private Long goodsId;

    /**
     * 约定面交地点 (可选，最长200字符)
     */
    @Size(max = 200, message = "面交地点不能超过200个字符")
    private String meetLocation;

    /**
     * 买家留言 (可选，最长500字符)
     */
    @Size(max = 500, message = "买家留言不能超过500个字符")
    private String buyerMessage;
}
