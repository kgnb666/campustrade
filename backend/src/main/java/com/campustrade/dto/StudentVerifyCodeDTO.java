package com.campustrade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 校园邮箱验证码核验 DTO
 */
@Data
public class StudentVerifyCodeDTO implements Serializable {

    @NotBlank(message = "校园邮箱不能为空")
    @Email(message = "校园邮箱格式不正确")
    private String schoolEmail;

    @NotBlank(message = "验证码不能为空")
    private String verifyCode;
}
