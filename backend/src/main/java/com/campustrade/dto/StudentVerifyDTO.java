package com.campustrade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 校园身份认证提交 DTO
 */
@Data
public class StudentVerifyDTO implements Serializable {

    @NotNull(message = "请选择所属学校")
    private Long schoolId;

    @NotBlank(message = "学号不能为空")
    private String studentNumber;

    @NotBlank(message = "校园邮箱不能为空")
    @Email(message = "校园邮箱格式不正确")
    private String schoolEmail;
}
