package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.dto.order.CancelOrderRequest;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.dto.order.OrderQueryRequest;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.exception.OrderBusinessException;
import com.campustrade.service.OrderService;
import com.campustrade.vo.order.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/**
 * 交易订单核心 REST API 控制器
 * 提供创建订单、我的订单分页、订单详情、卖家确认、取消订单及完成面交交易等闭环接口
 */
@Slf4j
@RestController
@RequestMapping({"/orders", "/api/orders"})
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 接口1: 创建订单
     * POST /orders
     * 权限：登录买家
     */
    @PostMapping
    public Result<OrderVO> createOrder(@CurrentUser User user, @Valid @RequestBody CreateOrderRequest request) {
        TradeOrder order = orderService.createOrder(
                user.getId(),
                request.getGoodsId(),
                request.getMeetLocation(),
                request.getBuyerMessage()
        );
        OrderVO vo = orderService.convertToVO(order);
        return Result.success("订单创建成功", vo);
    }

    /**
     * 接口2: 分页查询当前用户的订单 (我的订单)
     * GET /orders/my
     * 参数: role (BUYER/SELLER), status, page, size
     * 权限：登录用户
     */
    @GetMapping("/my")
    public Result<IPage<OrderVO>> getMyOrders(@CurrentUser User user, @Valid OrderQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new OrderQueryRequest();
        }
        IPage<OrderVO> pageResult = orderService.getMyOrders(user.getId(), queryRequest);
        return Result.success("获取订单列表成功", pageResult);
    }

    /**
     * 接口3: 查询订单详情
     * GET /orders/{id}
     * 权限：买家本人或卖家本人，非当事用户返回 403
     */
    @GetMapping("/{id}")
    public Result<OrderVO> getOrderDetail(@CurrentUser User user, @PathVariable("id") Long id) {
        try {
            OrderVO vo = orderService.getOrderDetail(id, user.getId());
            return Result.success("获取订单详情成功", vo);
        } catch (OrderBusinessException e) {
            if (e.getCode() == 403) {
                throw new AccessDeniedException(e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 接口4: 卖家确认订单接单
     * PUT /orders/{id}/confirm
     * 权限：仅限订单卖家本人，非卖家返回 403
     */
    @PutMapping("/{id}/confirm")
    public Result<OrderVO> confirmOrder(@CurrentUser User user, @PathVariable("id") Long id) {
        try {
            TradeOrder order = orderService.confirmOrder(id, user.getId());
            OrderVO vo = orderService.convertToVO(order);
            return Result.success("卖家确认接单成功", vo);
        } catch (OrderBusinessException e) {
            if (e.getCode() == 403) {
                throw new AccessDeniedException(e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 接口5: 取消订单
     * PUT /orders/{id}/cancel
     * 参数：cancelReason (必填且非空)
     * 权限：买家或卖家本人，非当事人返回 403
     */
    @PutMapping("/{id}/cancel")
    public Result<OrderVO> cancelOrder(@CurrentUser User user,
                                       @PathVariable("id") Long id,
                                       @Valid @RequestBody CancelOrderRequest request) {
        try {
            TradeOrder order = orderService.cancelOrder(id, user.getId(), request.getCancelReason());
            OrderVO vo = orderService.convertToVO(order);
            return Result.success("订单取消成功", vo);
        } catch (OrderBusinessException e) {
            if (e.getCode() == 403) {
                throw new AccessDeniedException(e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 接口6: 完成交易
     * PUT /orders/{id}/complete
     * 权限：买家或卖家本人，非当事人返回 403
     */
    @PutMapping("/{id}/complete")
    public Result<OrderVO> completeOrder(@CurrentUser User user, @PathVariable("id") Long id) {
        try {
            TradeOrder order = orderService.completeOrder(id, user.getId());
            OrderVO vo = orderService.convertToVO(order);
            return Result.success("交易完成", vo);
        } catch (OrderBusinessException e) {
            if (e.getCode() == 403) {
                throw new AccessDeniedException(e.getMessage());
            }
            throw e;
        }
    }
}
