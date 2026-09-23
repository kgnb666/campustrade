package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员审核队列里的一条认证申请。
 *
 * <p>审核人需要一眼看全三件事，缺任何一件都得再点进详情页：<b>是谁</b>（账号 + 昵称）、
 * <b>自称是谁</b>（学校 + 学号 + 姓名）、<b>有没有补充材料</b>（学生证照片地址，可为空——
 * 姓名与照片在本通道里都是可选的加分项，不是必填）。因此这里把这些字段一次性给全。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminVerifyReviewVO implements Serializable {

    /** student_verify 主键（审核接口的路径参数） */
    private Long id;

    private Long userId;

    private String username;

    private String nickname;

    private Long schoolId;

    private String schoolName;

    private String studentNumber;

    private String realName;

    private String evidenceUrl;

    private String verifyStatus;

    private String verifyStatusDesc;

    private String verifyMethod;

    private String reviewNote;

    private LocalDateTime submittedTime;

    private LocalDateTime reviewTime;

    /** 审核人用户名（已审核的记录才会带上，便于追溯） */
    private String reviewerName;
}
