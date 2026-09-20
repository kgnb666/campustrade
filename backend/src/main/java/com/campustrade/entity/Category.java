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
 * 商品分类实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 父级分类 ID (0 为根级别)
     */
    private Long parentId;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 分类图标标识
     */
    private String icon;

    /**
     * 显示排序
     */
    private Integer sort;

    /**
     * 状态: 1-启用, 0-禁用
     */
    private Integer status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
