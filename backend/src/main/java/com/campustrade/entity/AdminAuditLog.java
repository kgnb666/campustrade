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
 * 管理员操作审计流水持久化实体
 * 映射数据表: campus_trade.admin_audit_log
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("admin_audit_log")
public class AdminAuditLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("admin_id")
    private Long adminId;

    @TableField("admin_username")
    private String adminUsername;

    /**
     * 操作类型: OFF_SHELF_GOODS, SHIELD_REVIEW, FREEZE_USER, PASS_REPORT, REJECT_REPORT 等
     */
    @TableField("operation_type")
    private String operationType;

    /**
     * 目标实体类型: GOODS, REVIEW, USER, REPORT
     */
    @TableField("target_type")
    private String targetType;

    /**
     * 目标实体ID
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 变更前状态快照
     */
    @TableField("before_status")
    private String beforeStatus;

    /**
     * 变更后状态快照
     */
    @TableField("after_status")
    private String afterStatus;

    /**
     * 操作原因/处置批注 (最多500字)
     */
    @TableField("reason")
    private String reason;

    /**
     * 操作发起端 IP 地址
     */
    @TableField("ip_address")
    private String ipAddress;

    @TableField("created_time")
    private LocalDateTime createdTime;
}
