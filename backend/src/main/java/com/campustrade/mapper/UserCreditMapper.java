package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.UserCredit;
import org.apache.ibatis.annotations.Insert;
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
     * 并发安全的信用档案初始化：单条 INSERT ... ON CONFLICT DO NOTHING。
     *
     * <p>为什么不能用"先查后插 + catch 唯一键异常再查"：在 PostgreSQL 里，语句违反唯一约束会让
     * <b>整个事务进入 aborted 状态</b>，此后再执行任何 SQL（包括"重新查一次"）都会直接报
     * {@code current transaction is aborted}，表现为并发首次访问 500。
     * {@code ON CONFLICT DO NOTHING} 不抛异常、不污染事务，天然幂等。</p>
     *
     * @param id     主键（雪花 ID，由调用方生成，与本表既有 IdType.ASSIGN_ID 语义一致）
     * @param userId 用户 ID（表上已有唯一约束 user_credit_user_id_key）
     * @return 受影响行数：1 = 本次真正创建；0 = 已存在（并发竞争失败方）
     */
    @Insert("INSERT INTO campus_trade.user_credit " +
            "(id, user_id, credit_score, credit_level, trade_count, good_review_count, bad_review_count, " +
            " completed_count, cancel_count, created_time, updated_time) " +
            "VALUES (#{id}, #{userId}, 100, 'GOOD', 0, 0, 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (user_id) DO NOTHING")
    int insertCreditIfAbsent(@Param("id") Long id, @Param("userId") Long userId);

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

