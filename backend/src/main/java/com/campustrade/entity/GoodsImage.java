package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 商品图片实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("goods_image")
public class GoodsImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联商品 ID
     */
    private Long goodsId;

    /**
     * 图片访问 URL
     */
    private String imageUrl;

    /**
     * 排序权重 (升序)
     */
    private Integer sort;

    private LocalDateTime createdTime;
}
