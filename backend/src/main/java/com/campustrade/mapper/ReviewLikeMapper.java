package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.ReviewLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评价点赞数据访问 Mapper 接口
 */
@Mapper
public interface ReviewLikeMapper extends BaseMapper<ReviewLike> {
}
