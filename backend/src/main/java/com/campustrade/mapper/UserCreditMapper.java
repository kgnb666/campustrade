package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.UserCredit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户信用档案 Mapper 接口
 */
@Mapper
public interface UserCreditMapper extends BaseMapper<UserCredit> {

    /**
     * 悲观排他行锁查询用户信用档案 (FOR UPDATE)
     * 确保并发信用积分变动时串行化执行，杜绝数据更新丢失与账目漂移
     */
    @Select("SELECT * FROM campus_trade.user_credit WHERE user_id = #{userId} FOR UPDATE")
    UserCredit selectByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * 原子增减用户信用积分与统计计数 (数据库物理行级锁更新，杜绝并发更新丢失)
     */
    @Update("UPDATE campus_trade.user_credit " +
            "SET credit_score = LEAST(200, GREATEST(0, credit_score + #{delta})), " +
            "    credit_level = #{level}, " +
            "    completed_count = completed_count + #{completedDelta}, " +
            "    cancel_count = cancel_count + #{cancelDelta}, " +
            "    trade_count = trade_count + #{tradeDelta}, " +
            "    good_review_count = good_review_count + #{goodReviewDelta}, " +
            "    bad_review_count = bad_review_count + #{badReviewDelta}, " +
            "    updated_time = CURRENT_TIMESTAMP " +
            "WHERE user_id = #{userId}")
    int applyCreditAdjustment(
            @Param("userId") Long userId,
            @Param("delta") int delta,
            @Param("level") String level,
            @Param("completedDelta") long completedDelta,
            @Param("cancelDelta") long cancelDelta,
            @Param("tradeDelta") int tradeDelta,
            @Param("goodReviewDelta") int goodReviewDelta,
            @Param("badReviewDelta") int badReviewDelta
    );
}

