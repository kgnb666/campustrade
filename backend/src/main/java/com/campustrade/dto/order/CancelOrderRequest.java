package com.campustrade.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 取消订单请求入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrderRequest implements Serializable {

    /**
     * 订单取消原因 (必填，不可为空白，最长500字符)
     */
    @NotBlank(message = "取消原因不能为空")
    @Size(max = 500, message = "取消原因不能超过500个字符")
    private String cancelReason;
}
