package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.enums.VerifyMethod;
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

    /**
     * 认证通道：EMAIL（校园邮箱验证码）/ MANUAL（学生证人工审核）。
     * 取值来自 {@link VerifyMethod}，数据库 CHECK 只允许这两个值。
     */
    @Builder.Default
    @TableField("verify_method")
    private String verifyMethod = VerifyMethod.EMAIL.getCode();

    /** 人工通道填写的真实姓名（邮箱通道为空） */
    @TableField("real_name")
    private String realName;

    /** 人工通道的证明材料地址（学生证/校园卡照片，复用 /file/upload 的返回值） */
    @TableField("evidence_url")
    private String evidenceUrl;

    /** 管理员审核意见：驳回时必填（说明原因），通过时可选 */
    @TableField("review_note")
    private String reviewNote;

    /** 审核人（管理员）用户 ID */
    @TableField("reviewer_id")
    private Long reviewerId;

    /** 审核时间 */
    @TableField("review_time")
    private LocalDateTime reviewTime;

    @TableField("verify_time")
    private LocalDateTime verifyTime;

    @TableField("created_time")
    private LocalDateTime createdTime;
}
