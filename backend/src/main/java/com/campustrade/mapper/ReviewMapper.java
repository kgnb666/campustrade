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
     * 原子屏蔽违规评价 (VISIBLE -> AUDIT_REJECTED)
     * 带前置条件的状态跃迁：并发治理同一评价时只有一次能生效，杜绝"读-判断-写"竞态下的重复追缴。
     *
     * @return 受影响行数：1 = 屏蔽成功；0 = 评价已不是 VISIBLE（已被屏蔽或已删除）
     */
    @Update("UPDATE campus_trade.review SET status = 'AUDIT_REJECTED', updated_time = CURRENT_TIMESTAMP " +
            "WHERE id = #{reviewId} AND status = 'VISIBLE'")
    int shieldReviewAtomic(@Param("reviewId") Long reviewId);

    /**
     * 原子恢复被屏蔽的评价状态 (AUDIT_REJECTED -> VISIBLE)
     */
    @Update("UPDATE campus_trade.review SET status = 'VISIBLE', updated_time = CURRENT_TIMESTAMP WHERE id = #{reviewId} AND status = 'AUDIT_REJECTED'")
    int restoreReviewAtomic(@Param("reviewId") Long reviewId);
}
