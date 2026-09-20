package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper
 *
 * <h2>为什么这里全部是"定点更新"</h2>
 * MyBatis-Plus 默认 {@code updateStrategy = NOT_NULL}：用 {@code updateById(实体)} 时，实体上所有非空字段
 * 都会被拼进 SET 子句。于是"selectById 读整行 → 改一个字段 → updateById 写回"会隐式地把读取时刻的
 * 其余字段（status / view_count / price ...）一起写回，覆盖其他事务在此期间已经提交的修改。
 * 因此本表的状态跃迁与计数累加一律下沉为带前置条件的单条 UPDATE，并让调用方校验受影响行数。
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    /**
     * 原子累加商品浏览量（定点更新，绝不触碰 status 等其它列）。
     *
     * @return 受影响行数：1 = 已累加；0 = 商品不存在
     */
    @Update("UPDATE campus_trade.goods " +
            "SET view_count = COALESCE(view_count, 0) + #{delta}, updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId}")
    int incrementViewCount(@Param("goodsId") Long goodsId, @Param("delta") int delta);

    /**
     * 下单锁货：仅当商品当前确实处于 ON_SALE 时才锁定，天然串行化并发下单。
     *
     * @return 受影响行数：1 = 锁定成功；0 = 商品已不在售（被他人锁定/售出/下架）
     */
    @Update("UPDATE campus_trade.goods SET status = 'LOCKED', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId} AND status = 'ON_SALE'")
    int lockForOrder(@Param("goodsId") Long goodsId);

    /**
     * 取消订单后恢复在售：仅当商品仍处于 LOCKED 时才恢复。
     *
     * @return 受影响行数：1 = 已恢复；0 = 商品状态已不是 LOCKED
     */
    @Update("UPDATE campus_trade.goods SET status = 'ON_SALE', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId} AND status = 'LOCKED'")
    int restoreToOnSale(@Param("goodsId") Long goodsId);

    /**
     * 完成订单后将商品置为已售出：仅当商品仍处于 LOCKED 时才生效。
     *
     * @return 受影响行数：1 = 已置为 SOLD；0 = 商品状态已不是 LOCKED
     */
    @Update("UPDATE campus_trade.goods SET status = 'SOLD', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId} AND status = 'LOCKED'")
    int markSold(@Param("goodsId") Long goodsId);

    /**
     * 卖家下架/上架：交易中(LOCKED)与已售出(SOLD)的商品必须保持不变。
     *
     * @return 受影响行数：1 = 已变更；0 = 商品已锁单/售出（或不存在）
     */
    @Update("UPDATE campus_trade.goods SET status = #{status}, updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId} AND status NOT IN ('LOCKED', 'SOLD')")
    int updateStatusIfTradable(@Param("goodsId") Long goodsId, @Param("status") String status);

    /**
     * 管理员治理下架：仅当商品尚未处于 OFF_SHELF 时执行。
     * 注意：本条沿用治理侧既有的"可强制下架"语义（含 LOCKED/SOLD），只把整行回写改成定点更新。
     *
     * @return 受影响行数：1 = 已下架；0 = 商品已是 OFF_SHELF（或不存在）
     */
    @Update("UPDATE campus_trade.goods SET status = 'OFF_SHELF', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{goodsId} AND status <> 'OFF_SHELF'")
    int offShelfForGovernance(@Param("goodsId") Long goodsId);
}
