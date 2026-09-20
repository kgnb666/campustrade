package com.campustrade.enums;

import java.util.Locale;

/**
 * 学生认证状态枚举 —— {@code student_verify.verify_status} 取值的唯一真相源。
 *
 * <h2>取值与数据库约束一一对应</h2>
 * <p>取值集合与 V10 迁移中 {@code student_verify.verify_status} 的 CHECK 约束
 * {@code chk_student_verify_status_domain}（{@code CHECK (verify_status IN ('PENDING','SUCCESS'))}）
 * 完全一致；V12 的部分唯一索引也建立在 {@code verify_status = 'SUCCESS'} 上。</p>
 *
 * <p>此前这两个取值在四处各写了一遍字面量（实体默认值、服务层两个常量、以及查询条件），
 * 而"SUCCESS 只能由验证码核销成功这一条路径写入"是一条安全边界——
 * 边界散落在字面量里就无法用类型系统或一次改名来保证一致。本枚举把它收敛到一处：
 * 落库与接口中的取值都是 {@link #getCode()}，而 {@code getCode()} 就是枚举常量名。</p>
 *
 * <h2>为什么没有 FAILED</h2>
 * <p>V10 的 CHECK 约束只允许 {@code PENDING} 与 {@code SUCCESS}：当前没有"认证失败"这个落库状态——
 * 验证码错误/过期时申请行仍停留在 {@code PENDING}，用户可重新发起。把 {@code FAILED} 写进枚举
 * 会让"枚举取值 == 数据库取值域"这条不变量失真，因此不收录。</p>
 */
public enum StudentVerifyStatus {

    /** 待核销：申请已提交（或重发验证码后仍待核销），是核销路径唯一允许的起点 */
    PENDING("待核销"),

    /** 认证通过：只能由"验证码核销成功"写入（V12 的部分唯一索引只覆盖该状态） */
    SUCCESS("认证通过");

    private final String description;

    StudentVerifyStatus(String description) {
        this.description = description;
    }

    /**
     * 落库与接口中使用的状态字面量。
     */
    public String getCode() {
        return name();
    }

    /**
     * 中文描述（仅用于日志等可读性场景，不参与接口契约）。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断给定状态字面量是否等于本状态（大小写不敏感、两端空白容忍、null 安全）。
     */
    public boolean matches(String rawStatus) {
        return rawStatus != null && name().equalsIgnoreCase(rawStatus.trim());
    }

    /**
     * 解析状态字面量。
     *
     * @return 匹配的状态；null 或非法取值返回 {@code null}（由调用方决定如何拒绝）
     */
    public static StudentVerifyStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (StudentVerifyStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 全部合法取值的字面量数组（可用于日志、文档与断言）。
     */
    public static String[] allCodes() {
        StudentVerifyStatus[] statuses = values();
        String[] codes = new String[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            codes[i] = statuses[i].name();
        }
        return codes;
    }
}
