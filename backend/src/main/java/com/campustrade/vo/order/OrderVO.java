package com.campustrade.vo.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图对象 VO (禁止直接向前端暴露持久化 Entity)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO implements Serializable {

    /**
     * 订单主键 ID
     */
    private Long id;

    /**
     * 业务唯一订单号 (如 ORD202609171200001234)
     */
    private String orderNo;

    // ==========================================
    // 商品快照 (防篡改)
    // ==========================================
    private Long goodsId;
    private String goodsTitleSnapshot;
    private BigDecimal goodsPriceSnapshot;
    private String goodsImageSnapshot;
    private String meetLocation;
    private String buyerMessage;
    private String sellerReply;

    // ==========================================
    // 买家信息
    // ==========================================
    private Long buyerId;
    private String buyerUsername;
    private String buyerNickname;
    private String buyerAvatar;
    private OrderUserInfoVO buyer;

    // ==========================================
    // 卖家信息
    // ==========================================
    private Long sellerId;
    private String sellerUsername;
    private String sellerNickname;
    private String sellerAvatar;
    private OrderUserInfoVO seller;

    // ==========================================
    // 订单状态与描述
    // ==========================================
    private String orderStatus;
    private String statusDesc;

    // ==========================================
    // 取消信息
    // ==========================================
    private String cancelReason;
    private Long cancelledBy;

    // ==========================================
    // 全生命周期时间节点
    // ==========================================
    private LocalDateTime confirmedTime;
    private LocalDateTime completedTime;
    private LocalDateTime cancelledTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
