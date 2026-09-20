package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.StudentVerifyService;
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
     */
    @PostMapping("/verify")
    public Result<String> submitVerify(@Valid @RequestBody StudentVerifyDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        return studentVerifyService.submitVerify(username, dto);
    }

    /**
     * 输入邮箱验证码完成认证
     */
    @PostMapping("/verify/code")
    public Result<Void> verifyCode(@Valid @RequestBody StudentVerifyCodeDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        return studentVerifyService.verifyCode(username, dto);
    }
}
