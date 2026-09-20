package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户浏览足迹展示 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrowseHistoryVO {

    /**
     * 浏览历史 ID
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
     * 商品价格
     */
    private BigDecimal price;

    /**
     * 商品成色
     */
    private String conditionLevel;

    /**
     * 商品状态 (ON_SALE, SOLD, OFF_SHELF 等)
     */
    private String status;

    /**
     * 商品首图 URL
     */
    private String firstImageUrl;

    /**
     * 交易自提地点
     */
    private String location;

    /**
     * 浏览时间
     */
    private LocalDateTime browseTime;
}
