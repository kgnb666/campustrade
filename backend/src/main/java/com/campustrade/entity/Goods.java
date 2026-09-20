package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("goods")
public class Goods {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 卖家用户 ID
     */
    private Long sellerId;

    /**
     * 关联高校 ID
     */
    private Long schoolId;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 商品标题
     */
    private String title;

    /**
     * 商品描述
     */
    private String description;

    /**
     * 当前售价
     */
    private BigDecimal price;

    /**
     * 商品原价 (可选)
     */
    private BigDecimal originalPrice;

    /**
     * 成色级别 (如: 全新, 95新, 9成新, 8成新等)
     */
    private String conditionLevel;

    /**
     * 商品状态: DRAFT, ON_SALE, LOCKED, SOLD, OFF_SHELF
     */
    private String status;

    /**
     * 面交/交易地点
     */
    private String location;

    /**
     * 累计浏览量
     */
    private Integer viewCount;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
