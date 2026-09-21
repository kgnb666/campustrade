package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.TradeOrder;
import com.campustrade.vo.order.OrderTodoSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 交易订单持久层 Mapper 接口
 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    /**
     * 悲观排他行锁查询订单 (FOR UPDATE)
     * 确保状态机并发跃迁时串行化执行，杜绝并发竞态覆写
     */
    @Select("SELECT * FROM campus_trade.trade_order WHERE id = #{id} FOR UPDATE")
    TradeOrder selectByIdForUpdate(@Param("id") Long id);

    /**
     * 统计当前用户自己的"待办"订单数量（一次往返算出三个计数）。
     *
     * <h2>为什么必须是聚合 SQL 而不是复用列表接口</h2>
     * "待确认/待面交"可以从 order_status 推出，但"待评价"不能：订单处于 COMPLETED
     * 只说明交易结束，是否还需要"我"评价取决于 review 表里有没有 reviewer_id = 我的记录。
     * 若在前端逐单判断，就要为每一单再查一次评价状态（N+1），首页会因此发出一串请求。
     * 这里用 PostgreSQL 的 {@code count(*) FILTER (WHERE ...)} 一次算出三列。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>{@code pending_seller_confirm}：仅"我作为卖家"的待确认订单（买家视角不计入，
     *       因为买家在该状态下没有可做的动作）。</li>
     *   <li>{@code wait_meet}：买卖双方都计入（卖家确认后双方都要到场面交）。</li>
     *   <li>{@code to_review}：COMPLETED 且不存在 reviewer_id = 我的评价。
     *       只看"我有没有评过"，不看对方评没评——对方是否评价不是我该处理的事。</li>
     * </ul>
     *
     * <p>WHERE 先按 (buyer_id = 我 OR seller_id = 我) 收敛到"与我有关"的订单，
     * 聚合函数在过滤集上统计，因此<b>不会</b>看到其他用户的订单（数据隔离由 SQL 保证，
     * 而不是靠调用方记得传对参数）。无订单的用户仍会得到一行全 0（聚合无 GROUP BY 必返回一行）。</p>
     *
     * @param userId 当前登录用户 ID
     * @return 三项计数的汇总（永不为 null）
     */
    @Select("""
            SELECT
                count(*) FILTER (WHERE o.seller_id = #{userId} AND o.order_status = 'WAIT_SELLER_CONFIRM')
                    AS pending_seller_confirm,
                count(*) FILTER (WHERE o.order_status = 'WAIT_MEET')
                    AS wait_meet,
                count(*) FILTER (WHERE o.order_status = 'COMPLETED'
                    AND NOT EXISTS (
                        SELECT 1 FROM campus_trade.review r
                        WHERE r.order_id = o.id AND r.reviewer_id = #{userId}
                    ))
                    AS to_review
            FROM campus_trade.trade_order o
            WHERE o.buyer_id = #{userId} OR o.seller_id = #{userId}
            """)
    OrderTodoSummaryVO selectTodoSummary(@Param("userId") Long userId);
}

