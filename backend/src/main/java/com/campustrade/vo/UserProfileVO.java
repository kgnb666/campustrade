package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户详情视图对象 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO implements Serializable {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private String role;
    private String status;

    /**
     * 认证状态: NONE (未认证), PENDING (审核中), SUCCESS (已认证), FAILED (认证失败)
     */
    private String verifyStatus;

    /**
     * 认证所属学校名称
     */
    private String schoolName;

    /**
     * 学号 (脱敏或原始)
     */
    private String studentNumber;

    /**
     * 信用档案
     */
    private UserCreditVO credit;
}
