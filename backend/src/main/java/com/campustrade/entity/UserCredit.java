package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import com.campustrade.common.constant.CreditRule;

/**
 * 用户信用档案持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_credit")
public class UserCredit implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /**
     * 信用分（初始值与合法区间都来自 {@link CreditRule}：0 ~ 200，初始 100）。
     */
    @Builder.Default
    @TableField("credit_score")
    private Integer creditScore = CreditRule.SCORE_DEFAULT;

    @Builder.Default
    @TableField("trade_count")
    private Integer tradeCount = 0;

    @Builder.Default
    @TableField("good_review_count")
    private Integer goodReviewCount = 0;

    @Builder.Default
    @TableField("bad_review_count")
    private Integer badReviewCount = 0;

    /**
     * 实际履约完成订单总数
     */
    @Builder.Default
    @TableField("completed_count")
    private Long completedCount = 0L;

    /**
     * 主动取消或违约取消订单总数
     */
    @Builder.Default
    @TableField("cancel_count")
    private Long cancelCount = 0L;

    /**
     * 信用等级 (EXCELLENT, GOOD, FAIR, POOR)
     */
    @Builder.Default
    @TableField("credit_level")
    private String creditLevel = "GOOD";

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
