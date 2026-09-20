package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报工单流转状态枚举
 */
@Getter
public enum ReportStatus {

    /**
     * 待审核
     */
    PENDING("PENDING", "待审核"),

    /**
     * 举报属实，已采纳处置
     */
    HANDLED_VALID("HANDLED_VALID", "已采纳处置"),

    /**
     * 举报不属实，已驳回
     */
    HANDLED_INVALID("HANDLED_INVALID", "已驳回");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    ReportStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
