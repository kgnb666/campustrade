package com.campustrade.enums;

import java.util.Locale;

/**
 * 校园认证通道枚举 —— {@code student_verify.verify_method} 取值的唯一真相源。
 *
 * <p>两条通道解决的是同一个问题（"这个人是不是本校在读学生"），因此**认证结果语义完全相同**：
 * 下游只认 {@code verify_status = 'SUCCESS'}，不区分通道。通道只影响两件事：</p>
 * <ul>
 *   <li>材料与证据：邮箱通道靠"校园邮箱可达"，人工通道靠"学生证照片 + 管理员审核"；</li>
 *   <li>防重复维度：邮箱通道靠 V12 的 (school_id, school_email) 唯一索引，
 *       人工通道靠 V13 的 (school_id, student_number) 唯一索引。</li>
 * </ul>
 */
public enum VerifyMethod {

    /** 校园邮箱验证码（V1 起的原有通道） */
    EMAIL("校园邮箱验证码"),

    /** 学生证/校园卡照片 + 管理员人工审核（V13 新增，服务"没有学生邮箱"的高校） */
    MANUAL("学生证人工审核");

    private final String description;

    VerifyMethod(String description) {
        this.description = description;
    }

    public String getCode() {
        return name();
    }

    public String getDescription() {
        return description;
    }

    /** 解析通道字面量；null 或非法取值返回 {@code null}，由调用方决定如何拒绝。 */
    public static VerifyMethod fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (VerifyMethod method : values()) {
            if (method.name().equals(normalized)) {
                return method;
            }
        }
        return null;
    }
}
