package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.vo.VerifySubmitVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校园身份认证控制器
 */
@RestController
@RequestMapping("/student")
@RequiredArgsConstructor
public class StudentVerifyController {

    private final StudentVerifyService studentVerifyService;

    /**
     * 提交校园认证信息并申请验证码
     *
     * <p>默认响应中不包含验证码：验证码经真实邮件发送到校园邮箱（本地开发且 verify.mail-enabled=false
     * 时写入服务端日志），前端必须引导用户查收邮件后再输入。</p>
     *
     * <p>唯一例外是演示模式（verify.demo-mode-enabled 且邮箱命中 verify.demo-emails 白名单）：
     * 此时该邮箱的验证码随 {@code data} 返回，前端自动填入并标注"演示模式"。未列入白名单的邮箱
     * 仍然 {@code data} 为 null，接口契约不变。</p>
     */
    @PostMapping("/verify")
    public Result<VerifySubmitVO> submitVerify(@Valid @RequestBody StudentVerifyDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        VerifySubmitVO demoResult = studentVerifyService.submitVerify(username, dto);
        return Result.success(StudentVerifyService.VERIFY_CODE_SENT_MESSAGE, demoResult);
    }

    /**
     * 输入邮箱验证码完成认证
     */
    @PostMapping("/verify/code")
    public Result<Void> verifyCode(@Valid @RequestBody StudentVerifyCodeDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        studentVerifyService.verifyCode(username, dto);
        return Result.success(StudentVerifyService.VERIFY_SUCCESS_MESSAGE, null);
    }
}
