package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户浏览历史实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("browse_history")
public class BrowseHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("goods_id")
    private Long goodsId;

    @TableField("browse_time")
    private LocalDateTime browseTime;
}
