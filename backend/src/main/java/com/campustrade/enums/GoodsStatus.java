package com.campustrade.enums;

import java.util.Locale;

/**
 * 商品状态枚举 —— {@code goods.status} 取值的唯一真相源。
 *
 * <h2>取值与数据库约束一一对应</h2>
 * <p>取值集合与 V10 迁移中 {@code goods.status} 的 CHECK 约束
 * {@code CHECK (status IN ('DRAFT','ON_SALE','LOCKED','SOLD','OFF_SHELF'))} 完全一致；
 * 数据库里不允许出现这里的五个名字以外的取值，业务代码里也不允许再出现这五个字面量。</p>
 *
 * <h2>状态流转（现状汇总，未改变任何既有规则）</h2>
 * <ul>
 *   <li>{@code DRAFT} → 应用侧暂无入口（保留以匹配 CHECK 约束）</li>
 *   <li>{@code ON_SALE} --下单锁货--> {@code LOCKED} --订单取消--> {@code ON_SALE}</li>
 *   <li>{@code LOCKED} --面交完成--> {@code SOLD}</li>
 *   <li>{@code ON_SALE}/{@code OFF_SHELF} --卖家上下架--> 互转（{@code LOCKED}/{@code SOLD} 期间禁止）</li>
 *   <li>{@code OFF_SHELF} 亦由卖家逻辑删除、管理员治理下架写入</li>
 * </ul>
 *
 * <p>状态的 {@code code} 就是枚举常量名（{@link #getCode()} 返回 {@link #name()}），
 * 因此不存在"枚举名与字面量两处各写一遍、改一处漏一处"的可能。数据库列本身仍是字符串
 * （{@code Goods.status} 保持 {@code String}），所以接口响应体与落库内容不受任何影响。</p>
 */
public enum GoodsStatus {

    /** 草稿：仅由 CHECK 约束保留，应用侧当前不写入 */
    DRAFT("草稿"),

    /** 在售：公开列表唯一展示的状态，也是下单锁货的前置状态 */
    ON_SALE("在售"),

    /** 交易锁定中：已有买家下单，商品被锁定，卖家不可上下架、不可编辑 */
    LOCKED("交易中"),

    /** 已售出：订单完成后的终态，卖家不可上下架、不可编辑 */
    SOLD("已售出"),

    /** 已下架：卖家主动下架/逻辑删除，或管理员治理强制下架 */
    OFF_SHELF("已下架");

    private final String description;

    GoodsStatus(String description) {
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
    public static GoodsStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (GoodsStatus status : values()) {
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
        GoodsStatus[] statuses = values();
        String[] codes = new String[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            codes[i] = statuses[i].name();
        }
        return codes;
    }
}
