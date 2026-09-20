package com.campustrade.service;

import com.campustrade.common.Result;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;

/**
 * 校园身份认证服务接口
 */
public interface StudentVerifyService {

    /**
     * 提交认证申请并生成邮箱验证码
     */
    Result<String> submitVerify(String username, StudentVerifyDTO dto);

    /**
     * 校验邮箱验证码完成认证
     */
    Result<Void> verifyCode(String username, StudentVerifyCodeDTO dto);
}
