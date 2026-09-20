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
     * 分值变动大小 (正数或负数)
     */
    @TableField("change_score")
    private Integer changeScore;

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
