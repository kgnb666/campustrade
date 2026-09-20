package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 交易订单状态枚举
 * 严格管理订单流转状态，杜绝业务代码中直接使用魔法字符串
 */
@Getter
public enum OrderStatus {

    /**
     * 待卖家确认 (买家下单后的初始状态)
     */
    WAIT_SELLER_CONFIRM("WAIT_SELLER_CONFIRM", "待卖家确认"),

    /**
     * 待面交 (卖家已确认接单，双方线下验货面交中)
     */
    WAIT_MEET("WAIT_MEET", "待面交"),

    /**
     * 已完成 (面交完成，双方确认交割，终态)
     */
    COMPLETED("COMPLETED", "已完成"),

    /**
     * 已取消 (买家或卖家取消订单，终态)
     */
    CANCELLED("CANCELLED", "已取消");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    OrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
