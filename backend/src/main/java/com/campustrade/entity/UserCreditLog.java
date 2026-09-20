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

/**
 * 信用变更审计流水持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_credit_log")
public class UserCreditLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /**
     * 变动类型: TRADE_COMPLETED, TRADE_CANCEL_PENALTY, REVIEW_GOOD, REVIEW_BAD, ADMIN_ADJUST
     */
    @TableField("change_type")
    private String changeType;

    /**
     * 分值变动大小：<b>实际生效值</b>（即 after_score - before_score）。
     * 当余额被 [0,200] 区间截断时，该值会小于请求值（完全截断时为 0），
     * 从而保证对账恒等式 {@code 100 + SUM(change_score) == credit_score} 永远成立。
     */
    @TableField("change_score")
    private Integer changeScore;

    /**
     * 调用方请求的原始变动值（保留请求口径，便于审计区分"请求了多少"与"实际生效多少"）
     */
    @TableField("request_score")
    private Integer requestScore;

    /**
     * 幂等键：{@code changeType|relatedType|relatedId|actionKey}。
     * 同一次业务动作（含管理员治理动作）重试得到同一个键 → 不重复生效；
     * 不同次动作（例如"屏蔽 → 恢复 → 再次屏蔽"）键不同 → 各自生效。
     * 库侧由唯一索引 {@code uk_credit_log_idempotent(user_id, idem_key)} 兜底
     * （V10 迁移把该索引的键由业务维度升级为业务动作维度）。
     */
    @TableField("idem_key")
    private String idemKey;

    /**
     * 变动前积分
     */
    @TableField("before_score")
    private Integer beforeScore;

    /**
     * 变动后积分
     */
    @TableField("after_score")
    private Integer afterScore;

    /**
     * 关联业务类型 (如 ORDER, REVIEW, ADMIN)
     */
    @TableField("related_type")
    private String relatedType;

    /**
     * 关联业务主键ID
     */
    @TableField("related_id")
    private Long relatedId;

    /**
     * 变动原因或说明
     */
    @TableField("reason")
    private String reason;

    /**
     * 流水创建时间
     */
    @TableField("created_time")
    private LocalDateTime createdTime;
}
