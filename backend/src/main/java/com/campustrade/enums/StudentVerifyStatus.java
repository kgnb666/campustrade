package com.campustrade.enums;

import java.util.Locale;

/**
 * 学生认证状态枚举 —— {@code student_verify.verify_status} 取值的唯一真相源。
 *
 * <h2>取值与数据库约束一一对应</h2>
 * <p>取值集合与 {@code student_verify.verify_status} 的 CHECK 约束
 * {@code chk_student_verify_status_domain} 完全一致（V10 建立时只有 {@code PENDING/SUCCESS}，
 * V13 因"人工审核通道"加入了 {@code REJECTED}）；V12 的邮箱唯一索引与 V13 的学号唯一索引
 * 都建立在 {@code verify_status = 'SUCCESS'} 上。</p>
 *
 * <p>此前这些取值在四处各写了一遍字面量（实体默认值、服务层常量、查询条件），
 * 而"SUCCESS 只能由**受控的核销/审核路径**写入"是一条安全边界——
 * 边界散落在字面量里就无法用类型系统或一次改名来保证一致。本枚举把它收敛到一处：
 * 落库与接口中的取值都是 {@link #getCode()}，而 {@code getCode()} 就是枚举常量名。</p>
 *
 * <h2>为什么是 REJECTED 而不是 FAILED</h2>
 * <p>验证码错误/过期不属于落库状态：那种情况下申请行仍停留在 {@code PENDING}，用户可以重新发起
 * （见 {@link #PENDING}）。而人工审核通道里，"管理员驳回了这份材料"是一个**终态事实**，
 * 用户必须能区分"还在排队"与"已被驳回、需要改材料重提"，所以要有一个显式状态。
 * 它叫 {@code REJECTED}（针对"审核结论"）而不是 {@code FAILED}（会被误读成"认证尝试失败"）。</p>
 */
public enum StudentVerifyStatus {

    /**
     * 待核销 / 待审核：申请已提交，尚未产生认证结论。
     *
     * <p>描述里同时写出两种说法，是因为这个状态在两条通道上的"等待对象"不同：
     * 邮箱通道等学生自己输入验证码核销，人工通道等管理员审核。它是对外展示文案
     * （状态查询与审核队列都用它），因此不能只写其中一条通道的说法。</p>
     */
    PENDING("待核销/待审核"),

    /**
     * 认证通过：只能由两条受控路径写入 —— 邮箱验证码核销成功，或管理员审核通过。
     *
     * <p>两条通道的差异只记录在 {@code verify_method} 上；认证状态的语义完全一致，
     * 因此下游（发布商品闸门、卖家"已认证"标识）只认这一个状态。</p>
     */
    SUCCESS("认证通过"),

    /** 人工审核驳回：只由管理员审核写入，附带驳回原因（{@code review_note}），学生可修改材料重新提交 */
    REJECTED("审核未通过");

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
