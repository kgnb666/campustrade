package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 评价数据访问 Mapper 接口
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /**
     * 原子增加点赞数 (+1)
     */
    @Update("UPDATE campus_trade.review SET like_count = like_count + 1 WHERE id = #{reviewId}")
    int incrementLikeCount(@Param("reviewId") Long reviewId);

    /**
     * 原子减少点赞数 (-1)，配合 GREATEST 防下溢
     */
    @Update("UPDATE campus_trade.review SET like_count = GREATEST(0, like_count - 1) WHERE id = #{reviewId}")
    int decrementLikeCount(@Param("reviewId") Long reviewId);

    /**
     * 原子恢复被屏蔽的评价状态 (AUDIT_REJECTED -> VISIBLE)
     */
    @Update("UPDATE campus_trade.review SET status = 'VISIBLE', updated_time = CURRENT_TIMESTAMP WHERE id = #{reviewId} AND status = 'AUDIT_REJECTED'")
    int restoreReviewAtomic(@Param("reviewId") Long reviewId);
}
