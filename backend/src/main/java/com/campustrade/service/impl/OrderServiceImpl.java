package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.dto.CreateOrderDTO;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.dto.order.OrderQueryRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.OrderStatus;
import com.campustrade.exception.OrderBusinessException;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.CreditService;
import com.campustrade.service.OrderService;
import com.campustrade.service.order.OrderStateMachine;
import com.campustrade.vo.order.OrderUserInfoVO;
import com.campustrade.vo.order.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 交易订单业务核心实现类
 * 涵盖订单生命周期核心流转、状态机校验、快照防篡改、并发防超卖与交易信用核算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final TradeOrderMapper tradeOrderMapper;
    private final GoodsMapper goodsMapper;
    private final GoodsImageMapper goodsImageMapper;
    private final UserMapper userMapper;
    private final CreditService creditService;

    private static final DateTimeFormatter ORDER_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder createOrder(Long buyerId, CreateOrderDTO dto) {
        if (dto == null || dto.getGoodsId() == null) {
            throw new OrderBusinessException(400, "下单商品ID不能为空");
        }
        return createOrder(buyerId, dto.getGoodsId(), dto.getMeetLocation(), dto.getBuyerMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder createOrder(Long buyerId, CreateOrderRequest request) {
        if (request == null || request.getGoodsId() == null) {
            throw new OrderBusinessException(400, "下单商品ID不能为空");
        }
        return createOrder(buyerId, request.getGoodsId(), request.getMeetLocation(), request.getBuyerMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder createOrder(Long buyerId, Long goodsId, String meetLocation, String buyerMessage) {
        if (buyerId == null) {
            throw new OrderBusinessException(401, "请先登录后再下单");
        }
        if (goodsId == null) {
            throw new OrderBusinessException(400, "商品ID不能为空");
        }

        // 1. 查询目标商品并校验存在性
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new OrderBusinessException(404, "商品不存在");
        }

        // 2. 校验买家不能购买自己的商品
        if (Objects.equals(goods.getSellerId(), buyerId)) {
            throw new OrderBusinessException(400, "买家不能购买自己发布的商品");
        }

        // 3. 校验商品状态是否为 ON_SALE (在售)
        if (!"ON_SALE".equalsIgnoreCase(goods.getStatus())) {
            throw new OrderBusinessException(400, "商品非在售状态，无法下单");
        }

        // 3.1 原子锁货：UPDATE goods SET status='LOCKED' WHERE id=? AND status='ON_SALE'
        //     旧实现是"读整行 → setStatus(LOCKED) → updateById(实体)"，在 MyBatis-Plus NOT_NULL 策略下
        //     会把读取时刻的 view_count / price / title 等整行非空字段一并写回，覆盖并发提交
        //     （典型症状：并发浏览量同步后浏览量回退、卖家刚改的标题被抹掉）。
        //     条件更新本身持有该行的排他锁，因此并发下单天然串行化：后到者命中 0 行并被明确拒绝。
        int locked = goodsMapper.lockForOrder(goodsId);
        if (locked <= 0) {
            Goods latest = goodsMapper.selectById(goodsId);
            String currentStatus = (latest != null) ? String.valueOf(latest.getStatus()) : "记录已不存在";
            log.warn("下单锁货未命中（0 行受影响）: goodsId={}, buyerId={}, currentStatus={}",
                    goodsId, buyerId, currentStatus);
            throw new OrderBusinessException(400,
                    "商品当前状态为[" + currentStatus + "]，无法下单（可能已被其他买家锁定、已售出或已被卖家下架）");
        }

        // 4. 获取商品封面主图作为快照
        String coverImageUrl = null;
        List<GoodsImage> images = goodsImageMapper.selectList(
                new LambdaQueryWrapper<GoodsImage>()
                        .eq(GoodsImage::getGoodsId, goodsId)
                        .orderByAsc(GoodsImage::getSort)
        );
        if (images != null && !images.isEmpty()) {
            coverImageUrl = images.get(0).getImageUrl();
        }

        // 5. 生成唯一业务订单号 (ORDyyyyMMddHHmmssXXXX)
        String orderNo = generateOrderNo();

        // 6. 确定面交地点：优先入参，若为空则降级取商品预设地点
        String actualMeetLocation = meetLocation;
        if (actualMeetLocation == null || actualMeetLocation.trim().isEmpty()) {
            actualMeetLocation = goods.getLocation();
        }

        // 7. 构建订单实体，初始状态严格为 WAIT_SELLER_CONFIRM
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = TradeOrder.builder()
                .orderNo(orderNo)
                .buyerId(buyerId)
                .sellerId(goods.getSellerId())
                .goodsId(goods.getId())
                .schoolId(goods.getSchoolId())
                .goodsTitleSnapshot(goods.getTitle())
                .goodsPriceSnapshot(goods.getPrice())
                .goodsImageSnapshot(coverImageUrl)
                .meetLocation(actualMeetLocation)
                .buyerMessage(buyerMessage)
                .orderStatus(OrderStatus.WAIT_SELLER_CONFIRM)
                .createdTime(now)
                .updatedTime(now)
                .build();

        try {
            tradeOrderMapper.insert(order);
        } catch (DataIntegrityViolationException e) {
            log.warn("订单插入冲突 (可能触发同一商品局部唯一索引并发防重限制): goodsId={}", goodsId, e);
            throw new OrderBusinessException(400, "该商品已被其他买家下单锁定，请勿重复下单");
        }

        log.info("订单创建成功: orderId={}, orderNo={}, buyerId={}, sellerId={}, goodsId={}",
                order.getId(), orderNo, buyerId, goods.getSellerId(), goodsId);

        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder confirmOrder(Long orderId, Long sellerId) {
        if (orderId == null) {
            throw new OrderBusinessException(400, "订单ID不能为空");
        }
        if (sellerId == null) {
            throw new OrderBusinessException(401, "请先登录");
        }

        TradeOrder order = tradeOrderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new OrderBusinessException(404, "订单不存在");
        }

        // 校验操作人必须为卖家
        if (!Objects.equals(order.getSellerId(), sellerId)) {
            throw new OrderBusinessException(403, "只有卖家可以确认订单");
        }

        // 状态机流转校验: WAIT_SELLER_CONFIRM -> WAIT_MEET
        OrderStateMachine.validateTransition(order.getOrderStatus(), OrderStatus.WAIT_MEET);

        LocalDateTime now = LocalDateTime.now();
        order.setOrderStatus(OrderStatus.WAIT_MEET);
        order.setConfirmedTime(now);
        order.setUpdatedTime(now);
        tradeOrderMapper.updateById(order);

        log.info("卖家确认接单成功: orderId={}, sellerId={}", orderId, sellerId);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder cancelOrder(Long orderId, Long operatorId, String cancelReason) {
        if (orderId == null) {
            throw new OrderBusinessException(400, "订单ID不能为空");
        }
        if (operatorId == null) {
            throw new OrderBusinessException(401, "请先登录");
        }
        if (cancelReason == null || cancelReason.trim().isEmpty()) {
            throw new OrderBusinessException(400, "取消原因不能为空");
        }

        TradeOrder order = tradeOrderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new OrderBusinessException(404, "订单不存在");
        }

        // 校验操作人必须为买家或卖家
        boolean isBuyer = Objects.equals(order.getBuyerId(), operatorId);
        boolean isSeller = Objects.equals(order.getSellerId(), operatorId);
        if (!isBuyer && !isSeller) {
            throw new OrderBusinessException(403, "只有买家或卖家可以取消订单");
        }

        // 状态机流转校验: WAIT_SELLER_CONFIRM / WAIT_MEET -> CANCELLED
        OrderStatus previousStatus = order.getOrderStatus();
        OrderStateMachine.validateTransition(previousStatus, OrderStatus.CANCELLED);

        LocalDateTime now = LocalDateTime.now();
        order.setOrderStatus(OrderStatus.CANCELLED);
        order.setCancelledBy(operatorId);
        order.setCancelReason(cancelReason.trim());
        order.setCancelledTime(now);
        order.setUpdatedTime(now);
        tradeOrderMapper.updateById(order);

        // 恢复商品状态：定点更新 + 前置条件（UPDATE goods SET status='ON_SALE' WHERE id=? AND status='LOCKED'）。
        // 旧实现把整行商品读出来 updateById，会把读取时刻的 view_count 等字段一起写回，覆盖并发浏览量同步。
        int restored = goodsMapper.restoreToOnSale(order.getGoodsId());
        if (restored <= 0) {
            Goods latest = goodsMapper.selectById(order.getGoodsId());
            if (latest == null) {
                log.warn("订单取消后未能恢复商品在售：商品记录已不存在: orderId={}, goodsId={}", orderId, order.getGoodsId());
            } else if ("ON_SALE".equalsIgnoreCase(latest.getStatus())) {
                log.info("订单取消时商品已处于在售状态，无需恢复（幂等）: orderId={}, goodsId={}", orderId, order.getGoodsId());
            } else {
                log.error("订单取消后商品状态无法自动恢复: orderId={}, goodsId={}, currentStatus={}",
                        orderId, order.getGoodsId(), latest.getStatus());
                throw new OrderBusinessException(409,
                        "订单已取消，但商品当前状态为[" + latest.getStatus() + "]，无法自动恢复为在售，请联系平台处理");
            }
        }

        // 信用联动：卖家确认前取消不扣分；卖家确认后(WAIT_MEET阶段)取消，发起违约方扣1分，cancel_count + 1
        if (previousStatus == OrderStatus.WAIT_MEET) {
            creditService.deductCredit(
                    operatorId,
                    1,
                    CreditChangeType.TRADE_CANCEL_PENALTY,
                    "ORDER",
                    order.getId(),
                    "待面交阶段违约取消订单扣分: " + cancelReason.trim()
            );
        }

        log.info("订单取消成功: orderId={}, operatorId={}, cancelReason={}", orderId, operatorId, cancelReason);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TradeOrder completeOrder(Long orderId, Long operatorId) {
        if (orderId == null) {
            throw new OrderBusinessException(400, "订单ID不能为空");
        }
        if (operatorId == null) {
            throw new OrderBusinessException(401, "请先登录");
        }

        TradeOrder order = tradeOrderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new OrderBusinessException(404, "订单不存在");
        }

        // 校验操作人必须为买家或卖家
        boolean isBuyer = Objects.equals(order.getBuyerId(), operatorId);
        boolean isSeller = Objects.equals(order.getSellerId(), operatorId);
        if (!isBuyer && !isSeller) {
            throw new OrderBusinessException(403, "只有买家或卖家可以完成订单");
        }

        // 状态机流转校验: WAIT_MEET -> COMPLETED
        OrderStateMachine.validateTransition(order.getOrderStatus(), OrderStatus.COMPLETED);

        LocalDateTime now = LocalDateTime.now();
        order.setOrderStatus(OrderStatus.COMPLETED);
        order.setCompletedTime(now);
        order.setUpdatedTime(now);
        tradeOrderMapper.updateById(order);

        // 更新商品状态为 SOLD：定点更新 + 前置条件（UPDATE goods SET status='SOLD' WHERE id=? AND status='LOCKED'）
        int sold = goodsMapper.markSold(order.getGoodsId());
        if (sold <= 0) {
            Goods latest = goodsMapper.selectById(order.getGoodsId());
            String currentStatus = (latest != null) ? String.valueOf(latest.getStatus()) : "记录已不存在";
            log.error("订单完成但商品未能置为已售出: orderId={}, goodsId={}, currentStatus={}",
                    orderId, order.getGoodsId(), currentStatus);
            throw new OrderBusinessException(409,
                    "订单已完成，但商品当前状态为[" + currentStatus + "]，无法标记为已售出，请联系平台处理");
        }

        // 买家与卖家双方信用积分 + 2, completed_count + 1, trade_count + 1, 并沉淀审计流水
        creditService.addCredit(
                order.getBuyerId(),
                2,
                CreditChangeType.TRADE_COMPLETED,
                "ORDER",
                order.getId(),
                "订单交易顺利完成(买家履约)"
        );
        creditService.addCredit(
                order.getSellerId(),
                2,
                CreditChangeType.TRADE_COMPLETED,
                "ORDER",
                order.getId(),
                "订单交易顺利完成(卖家履约)"
        );

        log.info("订单面交完成: orderId={}, operatorId={}, buyerId={}, sellerId={}",
                orderId, operatorId, order.getBuyerId(), order.getSellerId());
        return order;
    }

    @Override
    public TradeOrder getOrderById(Long orderId) {
        if (orderId == null) {
            return null;
        }
        return tradeOrderMapper.selectById(orderId);
    }

    @Override
    public TradeOrder getOrderByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.trim().isEmpty()) {
            return null;
        }
        return tradeOrderMapper.selectOne(
                new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getOrderNo, orderNo.trim())
        );
    }

    @Override
    public IPage<OrderVO> getMyOrders(Long userId, OrderQueryRequest queryRequest) {
        if (userId == null) {
            throw new OrderBusinessException(401, "请先登录");
        }
        if (queryRequest == null) {
            queryRequest = new OrderQueryRequest();
        }

        int pageNum = queryRequest.getPage() != null && queryRequest.getPage() >= 1 ? queryRequest.getPage() : 1;
        int pageSize = queryRequest.getSize() != null && queryRequest.getSize() >= 1 && queryRequest.getSize() <= 100 ? queryRequest.getSize() : 10;

        Page<TradeOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TradeOrder> wrapper = new LambdaQueryWrapper<>();

        if ("SELLER".equalsIgnoreCase(queryRequest.getRole())) {
            wrapper.eq(TradeOrder::getSellerId, userId);
        } else {
            wrapper.eq(TradeOrder::getBuyerId, userId);
        }

        if (queryRequest.getStatus() != null && !queryRequest.getStatus().trim().isEmpty()) {
            try {
                OrderStatus statusEnum = OrderStatus.valueOf(queryRequest.getStatus().trim().toUpperCase());
                wrapper.eq(TradeOrder::getOrderStatus, statusEnum);
            } catch (IllegalArgumentException e) {
                throw new OrderBusinessException(400, "无效的订单状态: " + queryRequest.getStatus());
            }
        }

        wrapper.orderByDesc(TradeOrder::getCreatedTime);

        IPage<TradeOrder> orderPage = tradeOrderMapper.selectPage(page, wrapper);

        // 批量查询用户信息，避免 N+1
        Set<Long> userIds = new HashSet<>();
        for (TradeOrder o : orderPage.getRecords()) {
            if (o.getBuyerId() != null) userIds.add(o.getBuyerId());
            if (o.getSellerId() != null) userIds.add(o.getSellerId());
        }

        Map<Long, User> userMap = Collections.emptyMap();
        if (!userIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(userIds);
            if (users != null) {
                userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u, (k1, k2) -> k1));
            }
        }

        Map<Long, User> finalUserMap = userMap;
        List<OrderVO> voList = orderPage.getRecords().stream()
                .map(o -> convertToVOWithUserMap(o, finalUserMap))
                .collect(Collectors.toList());

        Page<OrderVO> resultPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    public OrderVO getOrderDetail(Long orderId, Long currentUserId) {
        if (orderId == null) {
            throw new OrderBusinessException(400, "订单ID不能为空");
        }
        if (currentUserId == null) {
            throw new OrderBusinessException(401, "请先登录");
        }

        TradeOrder order = tradeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new OrderBusinessException(404, "订单不存在");
        }

        boolean isBuyer = Objects.equals(order.getBuyerId(), currentUserId);
        boolean isSeller = Objects.equals(order.getSellerId(), currentUserId);
        if (!isBuyer && !isSeller) {
            throw new OrderBusinessException(403, "无权查看该订单详情");
        }

        return convertToVO(order);
    }

    @Override
    public OrderVO convertToVO(TradeOrder order) {
        if (order == null) {
            return null;
        }
        User buyer = order.getBuyerId() != null ? userMapper.selectById(order.getBuyerId()) : null;
        User seller = order.getSellerId() != null ? userMapper.selectById(order.getSellerId()) : null;
        return buildOrderVO(order, buyer, seller);
    }

    private OrderVO convertToVOWithUserMap(TradeOrder order, Map<Long, User> userMap) {
        if (order == null) {
            return null;
        }
        User buyer = order.getBuyerId() != null ? userMap.get(order.getBuyerId()) : null;
        User seller = order.getSellerId() != null ? userMap.get(order.getSellerId()) : null;
        return buildOrderVO(order, buyer, seller);
    }

    private OrderVO buildOrderVO(TradeOrder order, User buyer, User seller) {
        OrderUserInfoVO buyerVO = buyer != null ? OrderUserInfoVO.builder()
                .id(buyer.getId())
                .username(buyer.getUsername())
                .nickname(buyer.getNickname())
                .avatar(buyer.getAvatar())
                .build() : null;

        OrderUserInfoVO sellerVO = seller != null ? OrderUserInfoVO.builder()
                .id(seller.getId())
                .username(seller.getUsername())
                .nickname(seller.getNickname())
                .avatar(seller.getAvatar())
                .build() : null;

        return OrderVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .goodsId(order.getGoodsId())
                .goodsTitleSnapshot(order.getGoodsTitleSnapshot())
                .goodsPriceSnapshot(order.getGoodsPriceSnapshot())
                .goodsImageSnapshot(order.getGoodsImageSnapshot())
                .meetLocation(order.getMeetLocation())
                .buyerMessage(order.getBuyerMessage())
                .sellerReply(order.getSellerReply())
                .buyerId(order.getBuyerId())
                .buyerUsername(buyer != null ? buyer.getUsername() : null)
                .buyerNickname(buyer != null ? buyer.getNickname() : null)
                .buyerAvatar(buyer != null ? buyer.getAvatar() : null)
                .buyer(buyerVO)
                .sellerId(order.getSellerId())
                .sellerUsername(seller != null ? seller.getUsername() : null)
                .sellerNickname(seller != null ? seller.getNickname() : null)
                .sellerAvatar(seller != null ? seller.getAvatar() : null)
                .seller(sellerVO)
                .orderStatus(order.getOrderStatus() != null ? order.getOrderStatus().getCode() : null)
                .statusDesc(order.getOrderStatus() != null ? order.getOrderStatus().getDescription() : null)
                .cancelReason(order.getCancelReason())
                .cancelledBy(order.getCancelledBy())
                .confirmedTime(order.getConfirmedTime())
                .completedTime(order.getCompletedTime())
                .cancelledTime(order.getCancelledTime())
                .createdTime(order.getCreatedTime())
                .updatedTime(order.getUpdatedTime())
                .build();
    }

    private String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(ORDER_NO_DATE_FORMAT);
        int randomDigits = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "ORD" + timestamp + randomDigits;
    }
}
