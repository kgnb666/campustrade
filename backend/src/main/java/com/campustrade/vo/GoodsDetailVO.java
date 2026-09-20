package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品详情视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoodsDetailVO {

    private Long id;

    private Long sellerId;

    private Long schoolId;

    private String schoolName;

    private Long categoryId;

    private String categoryName;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private String conditionLevel;

    private String status;

    private String location;

    /**
     * 实时累计浏览量 (数据库基数 + Redis实时增量)
     */
    private Integer viewCount;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    /**
     * 商品图片列表 (按 sort 排序)
     */
    @Builder.Default
    private List<String> images = new ArrayList<>();

    /**
     * 商品标签列表
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    // ==========================================
    // 卖家信息与校园认证、信用等级
    // ==========================================
    private String sellerUsername;

    private String sellerNickname;

    private String sellerAvatar;

    /**
     * 卖家校园认证是否通过
     */
    private Boolean sellerVerified;

    /**
     * 卖家认证学校名称
     */
    private String sellerSchoolName;

    /**
     * 卖家信用积分
     */
    private Integer sellerCreditScore;

    /**
     * 卖家完成交易数
     */
    private Integer sellerTradeCount;

    /**
     * 卖家好评数
     */
    private Integer sellerGoodReviewCount;

    /**
     * 商品被收藏总数
     */
    private Long favoriteCount;

    /**
     * 当前登录用户是否已收藏该商品
     */
    private Boolean isFavorite;
}
