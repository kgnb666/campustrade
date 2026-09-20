package com.campustrade.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户个人资料修改 DTO
 */
@Data
public class UpdateProfileDTO implements Serializable {

    @Size(max = 50, message = "昵称长度不能超过 50 个字符")
    private String nickname;

    @Size(max = 500, message = "头像链接过长")
    private String avatar;

    @Size(max = 20, message = "手机号格式不正确")
    private String phone;
}
