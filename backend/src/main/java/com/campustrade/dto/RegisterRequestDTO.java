package com.campustrade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册请求 DTO
 */
@Data
public class RegisterRequestDTO implements Serializable {

    /**
     * 口令强度规则：长度 8~50，且必须同时包含字母与数字。
     *
     * <p>至少 8 位 + 混合字符集是最基本的口令强度下限：纯 6 位字母口令在现代 GPU 面前
     * 可在极短时间内被离线爆破，而 BCrypt 只能延缓、不能弥补口令本身的低熵。</p>
     */
    public static final String PASSWORD_COMPLEXITY_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).+$";

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度须在 3 到 50 个字符之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 50, message = "密码长度须在 8 到 50 个字符之间")
    @Pattern(regexp = PASSWORD_COMPLEXITY_REGEX, message = "密码必须同时包含字母和数字")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
