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
     * 商品状态。
     *
     * <p>取值域由 {@link com.campustrade.enums.GoodsStatus} 唯一定义，并与 V10 迁移的
     * CHECK 约束 {@code chk_goods_status_domain} 完全一致：
     * {@code DRAFT / ON_SALE / LOCKED / SOLD / OFF_SHELF}。</p>
     *
     * <p>本列刻意保持 {@code String}（数据库为 {@code VARCHAR(20)}），状态字面量不在业务代码中散落：
     * 判定与写入一律经 {@code GoodsStatus.xxx.getCode()} / {@code GoodsStatus.xxx.matches(...)}。
     * 其中 {@code DRAFT} 目前没有任何写入路径（仅由数据库约束保留）。</p>
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
