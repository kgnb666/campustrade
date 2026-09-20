package com.campustrade.service;

import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;

/**
 * 校园身份认证服务接口
 *
 * <h2>返回领域类型，不返回 Web 信封</h2>
 * <p>两个方法都无数据载荷（验证码只走邮件/本地日志通道），因此返回 {@code void}；
 * 失败一律以业务异常表达，成功提示文案由接口常量统一承载、由 Controller 包装为 {@code Result}。</p>
 */
public interface StudentVerifyService {

    /**
     * 申请验证码成功后的固定提示文案。
     *
     * <p><b>接口契约的一部分</b>：该文案与"响应 {@code data} 恒为 null"共同表达
     * "验证码只从校园邮箱获取，绝不随响应返回"，因此不允许在别处另写字面量。</p>
     */
    String VERIFY_CODE_SENT_MESSAGE = "验证码已发送至校园邮箱（5分钟内有效）";

    /**
     * 核验通过后的固定提示文案（接口契约的一部分，理由同 {@link #VERIFY_CODE_SENT_MESSAGE}）。
     */
    String VERIFY_SUCCESS_MESSAGE = "校园身份认证成功！已为您点亮高校专属认证标识";

    /**
     * 提交认证申请并通过真实邮件下发验证码到校园邮箱。
     *
     * <p>成功仅表示"验证码已下发"，<b>不携带验证码</b>（响应 {@code data} 恒为 null）：
     * 验证码只能从学生邮箱（或本地开发时的服务端日志）获取。</p>
     */
    void submitVerify(String username, StudentVerifyDTO dto);

    /**
     * 校验邮箱验证码完成认证
     */
    void verifyCode(String username, StudentVerifyCodeDTO dto);
}
