package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 信用等级枚举
 * 规则：
 * 130-200: EXCELLENT (信用极好)
 * 100-129: GOOD (信用良好)
 * 80-99: FAIR (信用中等)
 * 0-79: POOR (信用较低)
 */
@Getter
public enum CreditLevel {

    EXCELLENT(130, 200, "信用极好"),
    GOOD(100, 129, "信用良好"),
    FAIR(80, 99, "信用中等"),
    POOR(0, 79, "信用较低");

    private final int minScore;
    private final int maxScore;
    private final String description;

    @EnumValue
    @JsonValue
    private final String code;

    CreditLevel(int minScore, int maxScore, String description) {
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.description = description;
        this.code = this.name();
    }

    /**
     * 根据积分计算信用等级
     *
     * @param score 综合信用分
     * @return 对应的信用等级 (默认返回 GOOD)
     */
    public static CreditLevel fromScore(Integer score) {
        if (score == null) {
            return GOOD;
        }
        if (score >= 130) {
            return EXCELLENT;
        } else if (score >= 100) {
            return GOOD;
        } else if (score >= 80) {
            return FAIR;
        } else {
            return POOR;
        }
    }
}
