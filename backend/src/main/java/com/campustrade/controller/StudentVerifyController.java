package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.ManualVerifyRequest;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.vo.StudentVerifyStatusVO;
import com.campustrade.vo.VerifySubmitVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * 提交「无邮箱通道」认证材料（学校 + 学号 + 姓名 + 学生证照片）。
     *
     * <p>服务"没有学生邮箱"的高校：材料进入管理员审核队列，审核通过后点亮与邮箱通道**相同的**认证标识。
     * 提交成功不等于认证成功，因此提示文案与邮箱通道刻意不同（见 {@link StudentVerifyService#MANUAL_SUBMIT_MESSAGE}）。</p>
     */
    @PostMapping("/verify/manual")
    public Result<Void> submitManualVerify(@Valid @RequestBody ManualVerifyRequest dto) {
        String username = SecurityUtils.getCurrentUsername();
        studentVerifyService.submitManualVerify(username, dto);
        return Result.success(StudentVerifyService.MANUAL_SUBMIT_MESSAGE, null);
    }

    /**
     * 查询本人当前的认证状态：认证页据此区分 未认证 / 待审核 / 已认证 / 已驳回（含驳回原因）。
     */
    @GetMapping("/verify/status")
    public Result<StudentVerifyStatusVO> myVerifyStatus() {
        String username = SecurityUtils.getCurrentUsername();
        return Result.success(studentVerifyService.getMyVerifyStatus(username));
    }
}
