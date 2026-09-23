package com.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 「无邮箱通道」认证申请：学号 + 管理员人工审核。
 *
 * <p>它服务的场景很具体：部分高校不提供学生邮箱，邮箱验证码通道对其学生永远走不通，
 * 而校园认证是发布商品的硬前置。这里把"校园邮箱可达"换成"学号 + 管理员审核"，
 * 由管理员承担核验责任。</p>
 *
 * <p><b>必填只有学校与学号</b>：姓名、学生证照片都是**可选**的加分材料。
 * 门槛定这么低是刻意的——这条通道面向的正是"学校连邮箱都没有"的场景，
 * 再要求上传证件照，很可能把同一批学生又挡在门外（有人没有学生证照片、有人不愿上传证件）。
 * 需要更严格核验时，把开关收紧或要求补材料即可，而"可选"不会拦住任何人。</p>
 */
@Data
public class ManualVerifyRequest implements Serializable {

    @NotNull(message = "请选择所属学校")
    private Long schoolId;

    @NotBlank(message = "学号不能为空")
    @Size(max = 50, message = "学号长度不能超过 50 个字符")
    private String studentNumber;

    /** 真实姓名（可选：填了便于管理员核对，不填也能提交） */
    @Size(max = 50, message = "姓名长度不能超过 50 个字符")
    private String realName;

    /** 学生证/校园卡照片地址（可选；先调 /file/upload 上传，再把返回的 URL 放这里） */
    @Size(max = 255, message = "材料地址过长")
    private String evidenceUrl;
}
