package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 信用变动类型枚举
 */
@Getter
public enum CreditChangeType {

    /**
     * 交易顺利完成履约
     */
    TRADE_COMPLETED("TRADE_COMPLETED", "交易顺利完成履约"),

    /**
     * 接单后违约取消惩罚
     */
    TRADE_CANCEL_PENALTY("TRADE_CANCEL_PENALTY", "接单后违约取消惩罚"),

    /**
     * 交易好评加分
     */
    REVIEW_GOOD("REVIEW_GOOD", "交易好评加分"),

    /**
     * 交易差评扣分
     */
    REVIEW_BAD("REVIEW_BAD", "交易差评扣分"),

    /**
     * 管理员人工调控
     */
    ADMIN_ADJUST("ADMIN_ADJUST", "管理员人工调控");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    CreditChangeType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
