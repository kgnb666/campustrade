package com.campustrade.service;

import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.vo.VerifySubmitVO;

/**
 * 校园身份认证服务接口
 *
 * <h2>返回领域类型，不返回 Web 信封</h2>
 * <p>{@link #verifyCode} 无数据载荷，返回 {@code void}；失败一律以业务异常表达，
 * 成功提示文案由接口常量统一承载、由 Controller 包装为 {@code Result}。</p>
 */
public interface StudentVerifyService {

    /**
     * 申请验证码成功后的固定提示文案。
     *
     * <p><b>接口契约的一部分</b>：该文案与"响应 {@code data} 恒为 null"共同表达
     * "验证码只从校园邮箱获取，绝不随响应返回"，因此不允许在别处另写字面量。
     * 演示模式（{@code verify.demo-mode-enabled}）是这条契约唯一的例外，见 {@link #submitVerify}。</p>
     */
    String VERIFY_CODE_SENT_MESSAGE = "验证码已发送至校园邮箱（5分钟内有效）";

    /**
     * 核验通过后的固定提示文案（接口契约的一部分，理由同 {@link #VERIFY_CODE_SENT_MESSAGE}）。
     */
    String VERIFY_SUCCESS_MESSAGE = "校园身份认证成功！已为您点亮高校专属认证标识";

    /**
     * 提交认证申请并下发验证码。
     *
     * <p>正常通道（默认）：验证码经真实邮件送到校园邮箱（本地开发则写入服务端日志），
     * 返回 {@code null}，即响应 {@code data} 恒为 null。</p>
     *
     * <p>演示通道（{@code verify.demo-mode-enabled} 且邮箱命中 {@code verify.demo-emails} 白名单）：
     * 验证码不再下发，改为通过返回值交给 Controller 随响应返回。这条通道只对配置点名的邮箱生效，
     * 用于"没有学校邮箱也要演示认证流程"；未列入白名单的邮箱完全走正常通道。</p>
     *
     * @return 演示通道下携带验证码的 {@link VerifySubmitVO}；正常通道返回 {@code null}
     */
    VerifySubmitVO submitVerify(String username, StudentVerifyDTO dto);

    /**
     * 校验邮箱验证码完成认证
     */
    void verifyCode(String username, StudentVerifyCodeDTO dto);
}
