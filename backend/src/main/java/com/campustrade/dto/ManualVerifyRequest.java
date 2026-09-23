package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 「无邮箱通道」认证申请：学生证/校园卡照片 + 管理员人工审核。
 *
 * <p>它服务的场景很具体：部分高校不提供学生邮箱，邮箱验证码通道对其学生永远走不通，
 * 而校园认证是发布商品的硬前置。这里用"学号 + 真实姓名 + 学生证照片"替代"校园邮箱可达"，
 * 由管理员承担核验责任。</p>
 *
 * <p>材料不齐时宁可拒绝提交也不落库：一张没有照片的申请会把审核成本转嫁给管理员，
 * 而且申请人自己也不知道还差什么。</p>
 */
@Data
public class ManualVerifyRequest implements Serializable {

    @NotNull(message = "请选择所属学校")
    private Long schoolId;

    @NotBlank(message = "学号不能为空")
    @Size(max = 50, message = "学号长度不能超过 50 个字符")
    private String studentNumber;

    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "姓名长度不能超过 50 个字符")
    private String realName;

    /** 学生证/校园卡照片地址（先调 /file/upload 上传，再把返回的 URL 放这里） */
    @NotBlank(message = "请上传学生证或校园卡照片")
    private String evidenceUrl;
}
