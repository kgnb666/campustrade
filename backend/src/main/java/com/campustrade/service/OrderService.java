package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.CreateOrderDTO;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.dto.order.OrderQueryRequest;
import com.campustrade.entity.TradeOrder;
import com.campustrade.vo.order.OrderTodoSummaryVO;
import com.campustrade.vo.order.OrderVO;

/**
 * 交易订单业务服务接口
 */
public interface OrderService {

    /**
     * 买家创建订单
     *
     * @param buyerId 买家用户ID
     * @param dto     创建订单入参 DTO
     * @return 创建成功的订单实体
     */
    TradeOrder createOrder(Long buyerId, CreateOrderDTO dto);

    /**
     * 买家创建订单 (支持 CreateOrderRequest)
     *
     * @param buyerId 买家用户ID
     * @param request 下单请求入参
     * @return 创建成功的订单实体
     */
    TradeOrder createOrder(Long buyerId, CreateOrderRequest request);

    /**
     * 买家创建订单 (重载便捷方法)
     *
     * @param buyerId      买家用户ID
     * @param goodsId      商品ID
     * @param meetLocation 线下自提/面交地点
     * @param buyerMessage 买家留言
     * @return 创建成功的订单实体
     */
    TradeOrder createOrder(Long buyerId, Long goodsId, String meetLocation, String buyerMessage);

    /**
     * 卖家确认接单 (WAIT_SELLER_CONFIRM -> WAIT_MEET)
     *
     * @param orderId  订单ID
     * @param sellerId 卖家用户ID
     * @return 更新后的订单实体
     */
    TradeOrder confirmOrder(Long orderId, Long sellerId);

    /**
     * 取消订单 (WAIT_SELLER_CONFIRM 或 WAIT_MEET -> CANCELLED)
     *
     * @param orderId      订单ID
     * @param operatorId   操作人ID (买家或卖家)
     * @param cancelReason 取消原因 (必填)
     * @return 更新后的订单实体
     */
    TradeOrder cancelOrder(Long orderId, Long operatorId, String cancelReason);

    /**
     * 完成订单线下面交交割 (WAIT_MEET -> COMPLETED)
     *
     * @param orderId    订单ID
     * @param operatorId 操作人ID (买家或卖家)
     * @return 更新后的订单实体
     */
    TradeOrder completeOrder(Long orderId, Long operatorId);

    /**
     * 根据主键查询订单实体
     *
     * @param orderId 订单ID
     * @return 订单实体，不存在返回 null
     */
    TradeOrder getOrderById(Long orderId);

    /**
     * 根据业务订单号查询订单实体
     *
     * @param orderNo 业务订单号
     * @return 订单实体，不存在返回 null
     */
    TradeOrder getOrderByOrderNo(String orderNo);

    /**
     * 分页查询当前用户订单列表 (区分买家角色/卖家角色)
     *
     * @param userId       当前登录用户ID
     * @param queryRequest 查询请求入参
     * @return 订单分页结果 (OrderVO)
     */
    IPage<OrderVO> getMyOrders(Long userId, OrderQueryRequest queryRequest);

    /**
     * 查询订单详情 (严格校验买家/卖家访问权限)
     *
     * @param orderId       订单ID
     * @param currentUserId 当前登录用户ID
     * @return OrderVO
     */
    OrderVO getOrderDetail(Long orderId, Long currentUserId);

    /**
     * 统计当前用户自己的待办订单数量 (待我确认 / 待面交 / 待评价)
     *
     * <p>与 {@link #getMyOrders(Long, OrderQueryRequest)} 的区别：列表接口按"角色 + 状态"
     * 过滤，而"待评价"取决于评价表而不是订单状态，无法由列表接口推出。这里用一条聚合 SQL
     * 一次算出三项，供首页"我的待办"使用。只统计当前用户作为买家或卖家参与的订单。</p>
     *
     * @param userId 当前登录用户ID
     * @return 三项计数的汇总 VO（未登录时抛出 401 业务异常）
     */
    OrderTodoSummaryVO getTodoSummary(Long userId);

    /**
     * 将订单持久化实体转换为脱敏 VO 对象
     *
     * @param order 订单实体
     * @return OrderVO
     */
    OrderVO convertToVO(TradeOrder order);
}
