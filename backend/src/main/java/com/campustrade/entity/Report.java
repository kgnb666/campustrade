package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campustrade.enums.ReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报工单持久化实体
 * 映射数据表: campus_trade.report
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("report")
public class Report implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("reporter_id")
    private Long reporterId;

    /**
     * 目标实体类型: GOODS, REVIEW, USER
     */
    @TableField("target_type")
    private String targetType;

    /**
     * 目标实体ID
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 举报原因类型: FRAUD, COUNTERFEIT, ILLEGAL_PROHIBITED, HARASSMENT, MALICIOUS_REVIEW, OTHER
     */
    @TableField("reason_type")
    private String reasonType;

    /**
     * 举报原因详情描述 (最多500字)
     */
    @TableField("description")
    private String description;

    /**
     * 证据图片 URL 列表 (最多1000字)
     */
    @TableField("evidence_images")
    private String evidenceImages;

    /**
     * 工单流转状态: PENDING, HANDLED_VALID, HANDLED_INVALID
     */
    @Builder.Default
    @TableField("status")
    private String status = ReportStatus.PENDING.getCode();

    /**
     * 处理管理员用户 ID
     */
    @TableField("handled_by")
    private Long handledBy;

    /**
     * 处理完成时间
     */
    @TableField("handled_time")
    private LocalDateTime handledTime;

    /**
     * 处理答复/处置结果说明 (最多500字)
     */
    @TableField("handle_result")
    private String handleResult;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
