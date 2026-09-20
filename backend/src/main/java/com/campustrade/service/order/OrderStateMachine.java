package com.campustrade.service.order;

import com.campustrade.enums.OrderStatus;
import com.campustrade.exception.OrderBusinessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机组件
 * 严格控制交易订单状态生命周期流转，禁止非法流转
 */
@Component
public class OrderStateMachine {

    /**
     * 允许的状态流转映射表: from -> Set<to>
     */
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        // 1. 待卖家确认 -> 待面交, 已取消
        VALID_TRANSITIONS.put(OrderStatus.WAIT_SELLER_CONFIRM,
                EnumSet.of(OrderStatus.WAIT_MEET, OrderStatus.CANCELLED));

        // 2. 待面交 -> 已完成, 已取消
        VALID_TRANSITIONS.put(OrderStatus.WAIT_MEET,
                EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED));

        // 3. 已完成 (终态) -> 不允许流转到任何其他状态
        VALID_TRANSITIONS.put(OrderStatus.COMPLETED,
                Collections.emptySet());

        // 4. 已取消 (终态) -> 不允许流转到任何其他状态
        VALID_TRANSITIONS.put(OrderStatus.CANCELLED,
                Collections.emptySet());
    }

    /**
     * 检查订单状态是否允许从 from 状态流转到 to 状态
     *
     * @param from 源状态
     * @param to   目标状态
     * @return true 若允许流转，否则 false
     */
    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<OrderStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 校验订单状态流转合法性，若非法则直接抛出 OrderBusinessException
     *
     * @param from 源状态
     * @param to   目标状态
     * @throws OrderBusinessException 状态流转非法时抛出
     */
    public static void validateTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            String fromStr = from != null ? from.name() : "null";
            String toStr = to != null ? to.name() : "null";
            throw new OrderBusinessException(400, String.format("非法订单状态流转: 不允许从 [%s] 流转至 [%s]", fromStr, toStr));
        }
    }
}
