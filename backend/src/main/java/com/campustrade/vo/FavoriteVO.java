package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户收藏商品展示 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteVO {

    /**
     * 收藏记录 ID
     */
    private Long id;

    /**
     * 商品 ID
     */
    private Long goodsId;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 售价
     */
    private BigDecimal price;

    /**
     * 原价
     */
    private BigDecimal originalPrice;

    /**
     * 成色
     */
    private String conditionLevel;

    /**
     * 商品状态 (ON_SALE, SOLD, OFF_SHELF 等)
     */
    private String status;

    /**
     * 商品首图
     */
    private String firstImageUrl;

    /**
     * 交易自提地点
     */
    private String location;

    /**
     * 卖家 ID
     */
    private Long sellerId;

    /**
     * 卖家所属高校名称
     */
    private String schoolName;

    /**
     * 收藏时间
     */
    private LocalDateTime createdTime;
}
