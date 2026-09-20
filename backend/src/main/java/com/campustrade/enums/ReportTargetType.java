package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报目标实体类型枚举
 */
@Getter
public enum ReportTargetType {

    /**
     * 商品
     */
    GOODS("GOODS", "商品"),

    /**
     * 交易评价
     */
    REVIEW("REVIEW", "交易评价"),

    /**
     * 平台用户
     */
    USER("USER", "平台用户");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    ReportTargetType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
