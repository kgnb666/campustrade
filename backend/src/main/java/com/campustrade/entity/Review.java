package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campustrade.enums.ReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交易订单评价持久化实体
 * 映射数据表: campus_trade.review
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("review")
public class Review implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private Long orderId;

    @TableField("goods_id")
    private Long goodsId;

    @TableField("reviewer_id")
    private Long reviewerId;

    @TableField("reviewed_user_id")
    private Long reviewedUserId;

    /**
     * 评分星级: 1 ~ 5
     */
    @TableField("score")
    private Integer score;

    /**
     * 评价文本内容 (最多500字)
     */
    @TableField("content")
    private String content;

    /**
     * 快捷标签，多个逗号分隔或JSON字符串
     */
    @TableField("tags")
    private String tags;

    /**
     * 是否前台匿名展示
     */
    @Builder.Default
    @TableField("is_anonymous")
    private Boolean isAnonymous = false;

    /**
     * 评价展示状态: VISIBLE, AUDIT_REJECTED
     */
    @Builder.Default
    @TableField("status")
    private ReviewStatus status = ReviewStatus.VISIBLE;

    /**
     * 点赞计数 (>= 0)
     */
    @Builder.Default
    @TableField("like_count")
    private Integer likeCount = 0;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
