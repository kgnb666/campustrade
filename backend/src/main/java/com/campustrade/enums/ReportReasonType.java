package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报原因类型枚举
 */
@Getter
public enum ReportReasonType {

    /**
     * 虚假欺诈
     */
    FRAUD("FRAUD", "虚假欺诈"),

    /**
     * 假冒劣质
     */
    COUNTERFEIT("COUNTERFEIT", "假冒劣质"),

    /**
     * 违禁物品
     */
    ILLEGAL_PROHIBITED("ILLEGAL_PROHIBITED", "违禁物品"),

    /**
     * 骚扰谩骂/人身攻击
     */
    HARASSMENT("HARASSMENT", "骚扰谩骂/人身攻击"),

    /**
     * 恶意评价/虚假刷分
     */
    MALICIOUS_REVIEW("MALICIOUS_REVIEW", "恶意评价/虚假刷分"),

    /**
     * 其他原因
     */
    OTHER("OTHER", "其他原因");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    ReportReasonType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
