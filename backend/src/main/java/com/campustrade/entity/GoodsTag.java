package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品标签实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("goods_tag")
public class GoodsTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联商品 ID
     */
    private Long goodsId;

    /**
     * 标签名称
     */
    private String tagName;
}
