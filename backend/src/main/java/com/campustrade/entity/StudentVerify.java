package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campustrade.enums.StudentVerifyStatus;
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
     * 认证状态：取值来自 {@link StudentVerifyStatus}（数据库 CHECK 只允许 PENDING / SUCCESS）。
     * 列本身仍是字符串，接口响应体与落库内容不受影响。
     */
    @Builder.Default
    @TableField("verify_status")
    private String verifyStatus = StudentVerifyStatus.PENDING.getCode();

    @TableField("verify_time")
    private LocalDateTime verifyTime;

    @TableField("created_time")
    private LocalDateTime createdTime;
}
