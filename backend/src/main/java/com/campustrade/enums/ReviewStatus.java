package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 评价展示状态枚举
 */
@Getter
public enum ReviewStatus {

    /**
     * 正常可见
     */
    VISIBLE("VISIBLE", "正常可见"),

    /**
     * 违规审核屏蔽
     */
    AUDIT_REJECTED("AUDIT_REJECTED", "审核屏蔽");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    ReviewStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
