package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 当前用户的校园认证状态（认证页据此渲染"未认证 / 待审核 / 已认证 / 已驳回"四种形态）。
 *
 * <p>与 {@code /user/profile} 里的 {@code verifyStatus} 的区别：profile 只回答"认证过没有"，
 * 本 VO 还要回答"我提交的那份材料现在到哪一步了、被驳回的原因是什么"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentVerifyStatusVO implements Serializable {

    /** PENDING / SUCCESS / REJECTED；从未提交过材料时为 null */
    private String verifyStatus;

    /** EMAIL / MANUAL；从未提交过材料时为 null */
    private String verifyMethod;

    /** 中文状态描述（便于前端直接展示，避免前端再维护一份映射） */
    private String verifyStatusDesc;

    private Long schoolId;

    private String schoolName;

    private String studentNumber;

    /** 人工通道填写的姓名 */
    private String realName;

    /** 人工通道提交的证明材料 */
    private String evidenceUrl;

    /** 管理员审核意见（驳回原因） */
    private String reviewNote;

    /** 材料提交时间 */
    private LocalDateTime submittedTime;

    /** 审核时间 */
    private LocalDateTime reviewTime;

    /** 认证通过时间 */
    private LocalDateTime verifyTime;

    /** 便捷判断：是否已认证通过 */
    private boolean verified;
}
