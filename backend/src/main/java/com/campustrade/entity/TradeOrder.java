package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campustrade.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易订单持久化实体
 * 映射数据表: campus_trade.trade_order (由 mybatis-plus.global-config.db-config.schema 统一管理 schema)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("trade_order")
public class TradeOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务订单号，唯一 (例如: ORD202609171200001234)
     */
    private String orderNo;

    /**
     * 买家用户ID
     */
    private Long buyerId;

    /**
     * 卖家用户ID
     */
    private Long sellerId;

    /**
     * 目标商品ID
     */
    private Long goodsId;

    /**
     * 关联高校ID
     */
    private Long schoolId;

    /**
     * 商品标题快照 (防篡改)
     */
    private String goodsTitleSnapshot;

    /**
     * 商品价格快照 (防篡改)
     */
    private BigDecimal goodsPriceSnapshot;

    /**
     * 商品主图封面快照 (防篡改)
     */
    private String goodsImageSnapshot;

    /**
     * 线下自提/面交地点
     */
    private String meetLocation;

    /**
     * 买家下单留言
     */
    private String buyerMessage;

    /**
     * 卖家回复备注
     */
    private String sellerReply;

    /**
     * 订单状态: WAIT_SELLER_CONFIRM, WAIT_MEET, COMPLETED, CANCELLED
     */
    private OrderStatus orderStatus;

    /**
     * 订单取消原因
     */
    private String cancelReason;

    /**
     * 取消操作人ID
     */
    private Long cancelledBy;

    /**
     * 卖家确认接单时间
     */
    private LocalDateTime confirmedTime;

    /**
     * 订单面交完成时间
     */
    private LocalDateTime completedTime;

    /**
     * 订单取消时间
     */
    private LocalDateTime cancelledTime;

    /**
     * 订单创建时间
     */
    private LocalDateTime createdTime;

    /**
     * 订单最后更新时间
     */
    private LocalDateTime updatedTime;
}
