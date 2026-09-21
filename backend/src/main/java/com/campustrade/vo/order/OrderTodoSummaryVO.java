package com.campustrade.vo.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前登录用户的"待办"订单汇总 VO。
 *
 * <p>存在的理由：首页"我的待办"要显示三项数量，其中
 * <b>待评价</b>无法由订单状态推出——订单处于 COMPLETED 只说明交易结束了，
 * 是否还需要"我"去评价取决于 {@code campus_trade.review} 里有没有
 * reviewer_id = 我的记录。逐单查询评价状态会退化成 N+1，
 * 因此由后端用一条 SQL 聚合出三个计数。</p>
 *
 * <p>口径（全部限定在<b>当前用户自己的</b>订单之内，不做跨用户统计）：</p>
 * <ul>
 *   <li>{@link #pendingSellerConfirm}：我作为卖家的待确认订单
 *       （{@code seller_id = 我} 且 {@code order_status = 'WAIT_SELLER_CONFIRM'}）。</li>
 *   <li>{@link #waitMeet}：我需要参与面交的订单
 *       （{@code buyer_id = 我 或 seller_id = 我} 且 {@code order_status = 'WAIT_MEET'}），
 *       买卖双方都应看到，否则卖家视角会漏掉"该去面交了"这个提醒。</li>
 *   <li>{@link #toReview}：我还没评价的已完成订单
 *       （我参与 且 {@code order_status = 'COMPLETED'} 且不存在 reviewer_id = 我的评价）。</li>
 * </ul>
 *
 * <p>字段类型刻意用 {@link Integer} 而不是 {@code Long}：项目全局把 Long 序列化成 JSON 字符串
 * （为了避免雪花 ID 超过 2^53 时在 JS 侧丢精度），但这里的三个值都是"条数"而不是 ID，
 * 不存在精度问题；用 Integer 可以直接以 JSON 数字下发（{@code {"toReview":0}}），
 * 调用方不必再解析字符串。计数的量级也不可能逼近 int 上限。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTodoSummaryVO implements Serializable {

    /**
     * 待我确认（我作为卖家，订单状态为 WAIT_SELLER_CONFIRM 的数量）
     */
    private Integer pendingSellerConfirm;

    /**
     * 待面交（我作为买家或卖家，订单状态为 WAIT_MEET 的数量）
     */
    private Integer waitMeet;

    /**
     * 待评价（我参与、已完成、且我尚未提交评价的数量）
     */
    private Integer toReview;
}
