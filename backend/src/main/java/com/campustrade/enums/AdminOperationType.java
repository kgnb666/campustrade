package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 管理员治理操作类型枚举
 */
@Getter
public enum AdminOperationType {

    /**
     * 商品违规下架
     */
    OFF_SHELF_GOODS("OFF_SHELF_GOODS", "商品违规下架"),

    /**
     * 评价违规屏蔽
     */
    SHIELD_REVIEW("SHIELD_REVIEW", "评价违规屏蔽"),

    /**
     * 用户违规冻结
     */
    FREEZE_USER("FREEZE_USER", "用户违规冻结"),

    /**
     * 采纳有效举报
     */
    PASS_REPORT("PASS_REPORT", "采纳有效举报"),

    /**
     * 驳回无效举报
     */
    REJECT_REPORT("REJECT_REPORT", "驳回无效举报"),

    /**
     * 评价违规解除/恢复展示
     */
    RESTORE_REVIEW("RESTORE_REVIEW", "恢复评价展示"),

    /**
     * 人工审核通过校园认证（无邮箱通道）
     */
    PASS_STUDENT_VERIFY("PASS_STUDENT_VERIFY", "通过校园认证审核"),

    /**
     * 人工审核驳回校园认证（无邮箱通道）
     */
    REJECT_STUDENT_VERIFY("REJECT_STUDENT_VERIFY", "驳回校园认证审核");

    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    AdminOperationType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
