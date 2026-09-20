package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.CreditLevel;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 信用领域核心服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserCreditMapper userCreditMapper;
    private final UserCreditLogMapper userCreditLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit getOrCreateCredit(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        UserCredit credit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId)
        );

        if (credit == null) {
            LocalDateTime now = LocalDateTime.now();
            credit = UserCredit.builder()
                    .userId(userId)
                    .creditScore(100)
                    .creditLevel(CreditLevel.GOOD.name())
                    .tradeCount(0)
                    .goodReviewCount(0)
                    .badReviewCount(0)
                    .completedCount(0L)
                    .cancelCount(0L)
                    .createdTime(now)
                    .updatedTime(now)
                    .build();
            try {
                userCreditMapper.insert(credit);
                log.info("初始化用户信用档案成功: userId={}, creditScore=100, level=GOOD", userId);
            } catch (Exception e) {
                // 并发初始化竞争保护
                credit = userCreditMapper.selectOne(
                        new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId)
                );
                if (credit == null) {
                    throw e;
                }
            }
        }

        return credit;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit addCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("变动类型不能为空");
        }
        if (score == null || score <= 0) {
            return getOrCreateCredit(userId);
        }

        String changeTypeCode = type.getCode();

        // 1. 确保信用档案已初始化并加行级排他锁 (FOR UPDATE)，串行化同一用户的并发信用变动
        getOrCreateCredit(userId);
        UserCredit credit = userCreditMapper.selectByUserIdForUpdate(userId);
        if (credit == null) {
            credit = getOrCreateCredit(userId);
        }

        // 2. 幂等校验：检查是否已有相同业务维度的流水 (在排他行锁保护下校验最新提交的流水)
        LambdaQueryWrapper<UserCreditLog> query = new LambdaQueryWrapper<UserCreditLog>()
                .eq(UserCreditLog::getUserId, userId)
                .eq(UserCreditLog::getChangeType, changeTypeCode);
        if (relatedType != null) {
            query.eq(UserCreditLog::getRelatedType, relatedType);
        } else {
            query.isNull(UserCreditLog::getRelatedType);
        }
        if (relatedId != null) {
            query.eq(UserCreditLog::getRelatedId, relatedId);
        } else {
            query.isNull(UserCreditLog::getRelatedId);
        }

        if (userCreditLogMapper.selectCount(query) > 0) {
            log.warn("检测到重复增加信用积分请求，触发幂等拦截: userId={}, relatedType={}, relatedId={}, changeType={}",
                    userId, relatedType, relatedId, changeTypeCode);
            return credit;
        }

        // 3. 基于锁内权威数据计算前后分数与信用等级
        int beforeScore = credit.getCreditScore() != null ? credit.getCreditScore() : 100;
        int rawScore = beforeScore + score;
        int afterScore = Math.min(200, Math.max(0, rawScore));
        CreditLevel newLevel = CreditLevel.fromScore(afterScore);

        LocalDateTime now = LocalDateTime.now();

        // 4. 原子更新用户信用实体 (数据库行锁更新，杜绝并发更新丢失)
        long completedDelta = (type == CreditChangeType.TRADE_COMPLETED) ? 1L : 0L;
        int tradeDelta = (type == CreditChangeType.TRADE_COMPLETED) ? 1 : 0;
        int goodReviewDelta = (type == CreditChangeType.REVIEW_GOOD) ? 1 : 0;

        userCreditMapper.applyCreditAdjustment(
                userId,
                score,
                newLevel.name(),
                completedDelta,
                0L,
                tradeDelta,
                goodReviewDelta,
                0
        );

        credit.setCreditScore(afterScore);
        credit.setCreditLevel(newLevel.name());
        credit.setCompletedCount((credit.getCompletedCount() != null ? credit.getCompletedCount() : 0L) + completedDelta);
        credit.setTradeCount((credit.getTradeCount() != null ? credit.getTradeCount() : 0) + tradeDelta);
        credit.setGoodReviewCount((credit.getGoodReviewCount() != null ? credit.getGoodReviewCount() : 0) + goodReviewDelta);
        credit.setUpdatedTime(now);

        // 5. 生成审计流水记录
        UserCreditLog creditLog = UserCreditLog.builder()
                .userId(userId)
                .changeType(changeTypeCode)
                .changeScore(score)
                .beforeScore(beforeScore)
                .afterScore(afterScore)
                .relatedType(relatedType)
                .relatedId(relatedId)
                .reason(reason)
                .createdTime(now)
                .build();
        userCreditLogMapper.insert(creditLog);

        log.info("增加信用积分成功: userId={}, score=+{}, before={}, after={}, level={}",
                userId, score, beforeScore, afterScore, newLevel.name());

        return credit;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit deductCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("变动类型不能为空");
        }
        if (score == null || score <= 0) {
            return getOrCreateCredit(userId);
        }

        String changeTypeCode = type.getCode();
        int delta = Math.abs(score);

        // 1. 确保信用档案已初始化并加行级排他锁 (FOR UPDATE)，串行化同一用户的并发信用变动
        getOrCreateCredit(userId);
        UserCredit credit = userCreditMapper.selectByUserIdForUpdate(userId);
        if (credit == null) {
            credit = getOrCreateCredit(userId);
        }

        // 2. 幂等校验：检查是否已有相同业务维度的流水 (在排他行锁保护下校验最新提交的流水)
        LambdaQueryWrapper<UserCreditLog> query = new LambdaQueryWrapper<UserCreditLog>()
                .eq(UserCreditLog::getUserId, userId)
                .eq(UserCreditLog::getChangeType, changeTypeCode);
        if (relatedType != null) {
            query.eq(UserCreditLog::getRelatedType, relatedType);
        } else {
            query.isNull(UserCreditLog::getRelatedType);
        }
        if (relatedId != null) {
            query.eq(UserCreditLog::getRelatedId, relatedId);
        } else {
            query.isNull(UserCreditLog::getRelatedId);
        }

        if (userCreditLogMapper.selectCount(query) > 0) {
            log.warn("检测到重复扣除信用积分请求，触发幂等拦截: userId={}, relatedType={}, relatedId={}, changeType={}",
                    userId, relatedType, relatedId, changeTypeCode);
            return credit;
        }

        // 3. 基于锁内权威数据计算前后分数与信用等级
        int beforeScore = credit.getCreditScore() != null ? credit.getCreditScore() : 100;
        int rawScore = beforeScore - delta;
        int afterScore = Math.min(200, Math.max(0, rawScore));
        CreditLevel newLevel = CreditLevel.fromScore(afterScore);

        LocalDateTime now = LocalDateTime.now();

        // 4. 原子更新用户信用实体 (数据库行锁更新，杜绝并发更新丢失)
        long cancelDelta = (type == CreditChangeType.TRADE_CANCEL_PENALTY) ? 1L : 0L;
        int badReviewDelta = (type == CreditChangeType.REVIEW_BAD) ? 1 : 0;

        userCreditMapper.applyCreditAdjustment(
                userId,
                -delta,
                newLevel.name(),
                0L,
                cancelDelta,
                0,
                0,
                badReviewDelta
        );

        credit.setCreditScore(afterScore);
        credit.setCreditLevel(newLevel.name());
        credit.setCancelCount((credit.getCancelCount() != null ? credit.getCancelCount() : 0L) + cancelDelta);
        credit.setBadReviewCount((credit.getBadReviewCount() != null ? credit.getBadReviewCount() : 0) + badReviewDelta);
        credit.setUpdatedTime(now);

        // 5. 生成审计流水记录 (扣减分值为负数)
        UserCreditLog creditLog = UserCreditLog.builder()
                .userId(userId)
                .changeType(changeTypeCode)
                .changeScore(-delta)
                .beforeScore(beforeScore)
                .afterScore(afterScore)
                .relatedType(relatedType)
                .relatedId(relatedId)
                .reason(reason)
                .createdTime(now)
                .build();
        userCreditLogMapper.insert(creditLog);

        log.info("扣除信用积分成功: userId={}, score=-{}, before={}, after={}, level={}",
                userId, delta, beforeScore, afterScore, newLevel.name());

        return credit;
    }
}
