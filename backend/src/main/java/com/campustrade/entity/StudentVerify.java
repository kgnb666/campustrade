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
 * 学生认证持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("student_verify")
public class StudentVerify implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("student_number")
    private String studentNumber;

    @TableField("school_email")
    private String schoolEmail;

    @TableField("verify_code")
    private String verifyCode;

    /**
     * 认证状态: PENDING, SUCCESS, FAILED
     */
    @Builder.Default
    @TableField("verify_status")
    private String verifyStatus = "PENDING";

    @TableField("verify_time")
    private LocalDateTime verifyTime;

    @TableField("created_time")
    private LocalDateTime createdTime;
}
