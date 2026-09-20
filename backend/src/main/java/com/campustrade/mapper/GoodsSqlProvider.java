package com.campustrade.mapper;

import com.campustrade.enums.GoodsStatus;

/**
 * {@code goods} 表定点状态更新的 SQL 提供者。
 *
 * <h2>为什么需要它</h2>
 * <p>MyBatis 注解上的 SQL 必须是<b>编译期常量</b>，而 Java 枚举常量不是常量表达式，
 * 因此 {@code @Update("... status = 'LOCKED' ...")} 无法写成
 * {@code GoodsStatus.LOCKED.getCode()}。若为了可用而把字面量留在注解里，
 * {@link GoodsStatus} 就不再是唯一真相源（改枚举漏改 SQL 会静默产生状态漂移）。</p>
 *
 * <p>把 SQL 的拼装下沉到本类（MyBatis {@code @UpdateProvider}）后，
 * 所有状态字面量都来自 {@link GoodsStatus#getCode()}，源码中不再有任何散落的状态字符串；
 * SQL 仍带参数占位符（{@code #{goodsId}}），不引入注入面。</p>
 */
public final class GoodsSqlProvider {

    private GoodsSqlProvider() {
    }

    /**
     * 下单锁货：仅当商品仍在售时锁定。
     */
    public static String lockForOrder() {
        return transitionSql(GoodsStatus.ON_SALE, GoodsStatus.LOCKED);
    }

    /**
     * 取消订单后恢复在售：仅当商品仍处于锁定中时恢复。
     */
    public static String restoreToOnSale() {
        return transitionSql(GoodsStatus.LOCKED, GoodsStatus.ON_SALE);
    }

    /**
     * 完成订单后置为已售出：仅当商品仍处于锁定中时生效。
     */
    public static String markSold() {
        return transitionSql(GoodsStatus.LOCKED, GoodsStatus.SOLD);
    }

    /**
     * 卖家下架：交易中与已售出的商品必须保持不变，因此仅排除这两个状态。
     */
    public static String offShelfForSeller() {
        return "UPDATE campus_trade.goods SET status = #{status}, updated_time = CURRENT_TIMESTAMP "
                + "WHERE id = #{goodsId} AND status NOT IN ('"
                + GoodsStatus.LOCKED.getCode() + "', '"
                + GoodsStatus.SOLD.getCode() + "')";
    }

    /**
     * 管理员治理下架：沿用治理侧既有的"可强制下架"语义（含 LOCKED/SOLD），
     * 只排除已经是下架状态的行。
     */
    public static String offShelfForGovernance() {
        return "UPDATE campus_trade.goods SET status = '" + GoodsStatus.OFF_SHELF.getCode()
                + "', updated_time = CURRENT_TIMESTAMP WHERE id = #{goodsId} AND status <> '"
                + GoodsStatus.OFF_SHELF.getCode() + "'";
    }

    /**
     * 定点状态跃迁：仅当当前状态等于 {@code from} 时改为 {@code to}。
     */
    private static String transitionSql(GoodsStatus from, GoodsStatus to) {
        return "UPDATE campus_trade.goods SET status = '" + to.getCode()
                + "', updated_time = CURRENT_TIMESTAMP WHERE id = #{goodsId} AND status = '"
                + from.getCode() + "'";
    }
}
