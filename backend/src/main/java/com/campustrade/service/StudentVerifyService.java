package com.campustrade.service;

import com.campustrade.common.Result;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;

/**
 * 校园身份认证服务接口
 */
public interface StudentVerifyService {

    /**
     * 提交认证申请并通过真实邮件下发验证码到校园邮箱。
     *
     * <p>返回值只表示"验证码已下发"，<b>不携带验证码</b>（{@code data} 恒为 null）：
     * 验证码只能从学生邮箱（或本地开发时的服务端日志）获取。</p>
     */
    Result<Void> submitVerify(String username, StudentVerifyDTO dto);

    /**
     * 校验邮箱验证码完成认证
     */
    Result<Void> verifyCode(String username, StudentVerifyCodeDTO dto);
}
