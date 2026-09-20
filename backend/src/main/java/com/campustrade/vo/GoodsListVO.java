package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表展示视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoodsListVO {

    private Long id;

    private Long sellerId;

    private Long schoolId;

    private String schoolName;

    private Long categoryId;

    private String categoryName;

    private String title;

    /**
     * 商品主图/首图
     */
    private String coverImage;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private String conditionLevel;

    private String status;

    private String location;

    private Integer viewCount;

    private LocalDateTime createdTime;
}
