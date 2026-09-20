package com.campustrade.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.campustrade.common.constant.CreditRule;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 信用等级枚举。
 *
 * <p>每个等级的区间端点只在本枚举的常量声明处写一次，{@link #fromScore(Integer)} 直接读枚举字段
 * 判定（此前 {@code fromScore} 里另写了一遍 130/100/80，与常量声明构成两处真相，
 * 调整区间时极易只改一处）。</p>
 *
 * <p>信用分的整体有效区间由 {@link CreditRule} 定义：{@link #POOR} 的下界与
 * {@link #EXCELLENT} 的上界分别取自 {@code CreditRule.SCORE_MIN} / {@code SCORE_MAX}，
 * 使"等级表覆盖整个有效分数区间"成为编译期可见的事实，而不是靠人工核对。</p>
 */
@Getter
public enum CreditLevel {

    EXCELLENT(130, CreditRule.SCORE_MAX, "信用极好"),
    GOOD(100, 129, "信用良好"),
    FAIR(80, 99, "信用中等"),
    POOR(CreditRule.SCORE_MIN, 79, "信用较低");

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
     * 根据积分计算信用等级。
     *
     * @param score 综合信用分
     * @return 对应区间内的信用等级；{@code null} 与越界分数按既有约定兜底
     *         （null → {@link #GOOD}，高于上界 → {@link #EXCELLENT}，低于下界 → {@link #POOR}）
     */
    public static CreditLevel fromScore(Integer score) {
        if (score == null) {
            return GOOD;
        }
        for (CreditLevel level : values()) {
            if (score >= level.minScore && score <= level.maxScore) {
                return level;
            }
        }
        // 越界分数（理论上会被 CreditService 的 [SCORE_MIN, SCORE_MAX] 截断，此处兜底）
        return score > CreditRule.SCORE_MAX ? EXCELLENT : POOR;
    }

    /**
     * 本等级是否覆盖取值范围完整且与相邻等级不重叠（供测试断言等级表自洽）。
     */
    public boolean contains(int score) {
        return score >= minScore && score <= maxScore;
    }
}
