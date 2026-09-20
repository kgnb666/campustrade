package com.campustrade.dto.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单分页查询请求入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderQueryRequest implements Serializable {

    /**
     * 角色视角: BUYER (我买到的，默认), SELLER (我卖出的)
     */
    @Builder.Default
    private String role = "BUYER";

    /**
     * 订单状态筛选: WAIT_SELLER_CONFIRM, WAIT_MEET, COMPLETED, CANCELLED (可选)
     */
    private String status;

    /**
     * 当前页码，默认 1，最小为 1
     */
    @Min(value = 1, message = "页码必须大于等于1")
    @Builder.Default
    private Integer page = 1;

    /**
     * 每页数量，默认 10，范围 1~100
     */
    @Min(value = 1, message = "每页数量最小为1")
    @Max(value = 100, message = "每页数量最大为100")
    @Builder.Default
    private Integer size = 10;
}
