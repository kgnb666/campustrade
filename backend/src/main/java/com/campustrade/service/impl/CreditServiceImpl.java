package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.campustrade.common.constant.CreditRule;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.CreditLevel;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 信用领域核心服务实现类
 *
 * <h2>账目自洽（对账不变量）</h2>
 * 流水 {@code change_score} 记录的是<b>实际生效值</b>（{@code after_score - before_score}），
 * 而不是调用方请求值；请求值另行保留在 {@code request_score}。因此对任意用户恒有：
 * <pre>100 + SUM(change_score) == credit_score</pre>
 * 一旦余额被 [0,200] 区间截断（例如已到 200 分仍请求 +3），实际值为 0，对账依然成立。
 *
 * <h2>幂等键语义</h2>
 * 幂等键为 {@code changeType|relatedType|relatedId|actionKey}（见 {@link #buildIdemKey}），
 * 库侧由 {@code uk_credit_log_idempotent(user_id, idem_key)} 唯一索引兜底。
 * <ul>
 *   <li><b>同一次业务动作重试</b>：键完全相同 → 直接短路返回，不再变动余额，并打出
 *       {@code [CREDIT-IDEMPOTENT-BLOCK]} 告警日志（绝不静默丢弃）；</li>
 *   <li><b>不同次业务动作</b>：键不同 → 各自生效。这正是"屏蔽(REVIEW) → 恢复(REVIEW_RESTORE)
 *       → 再次屏蔽(REVIEW)"在旧实现下第三次追缴被误拦的根因：旧实现只用
 *       changeType + relatedType + relatedId 组成键，第三次与第一次完全相同。
 *       现在治理动作把"本次动作对应的审计日志 ID"作为 actionKey 纳入键，三次动作键互不相同，
 *       而同一动作的重试仍得到同一个键。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final UserCreditMapper userCreditMapper;
    private final UserCreditLogMapper userCreditLogMapper;

    /** 幂等键字段分隔符（必须与 V10 迁移脚本中的回填表达式保持一致）。 */
    private static final String IDEM_KEY_SEPARATOR = "|";

    /** 信用分有效区间与初始值：全部取自 {@link CreditRule}（业务规则数值的唯一真相源）。 */
    private static final int SCORE_MIN = CreditRule.SCORE_MIN;
    private static final int SCORE_MAX = CreditRule.SCORE_MAX;
    private static final int SCORE_DEFAULT = CreditRule.SCORE_DEFAULT;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit getOrCreateCredit(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        UserCredit credit = selectByUserId(userId);
        if (credit != null) {
            return credit;
        }

        // 并发安全的初始化：单条 INSERT ... ON CONFLICT (user_id) DO NOTHING。
        // 旧的"先查后插 + catch 唯一键异常再查"在 PostgreSQL 下会因事务被标记为 aborted 而二次失败（接口 500）。
        int created = userCreditMapper.insertCreditIfAbsent(IdWorker.getId(), userId);
        credit = selectByUserId(userId);
        if (credit == null) {
            throw new IllegalStateException("用户信用档案初始化后仍无法读取: userId=" + userId);
        }
        if (created > 0) {
            log.info("初始化用户信用档案成功: userId={}, creditScore={}, level=GOOD", userId, SCORE_DEFAULT);
        } else {
            log.info("并发初始化竞争：用户信用档案已由其他事务创建，复用既有档案 userId={}", userId);
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
        return addCredit(userId, score, type, relatedType, relatedId, reason, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit addCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    ) {
        return applyScoreChange(userId, score, true, type, relatedType, relatedId, reason, actionKey);
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
        return deductCredit(userId, score, type, relatedType, relatedId, reason, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit deductCredit(
            Long userId,
            Integer score,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    ) {
        return applyScoreChange(userId, score, false, type, relatedType, relatedId, reason, actionKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserCredit applyDelta(
            Long userId,
            Integer delta,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    ) {
        if (delta == null || delta == 0) {
            // 规则值为 0（例如 3 星评价）时既不调整余额也不产生审计流水，
            // 与"调用方根本不发起调用"的旧行为逐字等价，只是把"是否为 0"的判断收进领域层。
            if (userId == null) {
                throw new IllegalArgumentException("用户ID不能为空");
            }
            log.info("信用变动幅度为 0，按规则不做任何调整: userId={}, changeType={}, relatedType={}, relatedId={}, actionKey={}",
                    userId, type == null ? null : type.getCode(), relatedType, relatedId, actionKey);
            return getOrCreateCredit(userId);
        }
        return applyScoreChange(userId, Math.abs(delta), delta > 0, type, relatedType, relatedId, reason, actionKey);
    }

    /**
     * 信用变动统一实现（加分与扣分共用）。
     *
     * @param magnitude 请求变动幅度（恒为非负数）
     * @param increase  true = 加分，false = 扣分
     */
    private UserCredit applyScoreChange(
            Long userId,
            Integer magnitude,
            boolean increase,
            CreditChangeType type,
            String relatedType,
            Long relatedId,
            String reason,
            String actionKey
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("变动类型不能为空");
        }
        if (magnitude == null || magnitude <= 0) {
            log.warn("信用变动请求幅度非正数，本次不做任何变动: userId={}, requestedMagnitude={}, changeType={}",
                    userId, magnitude, type.getCode());
            return getOrCreateCredit(userId);
        }

        String changeTypeCode = type.getCode();
        int requestedScore = increase ? magnitude : -magnitude;
        String idemKey = buildIdemKey(changeTypeCode, relatedType, relatedId, actionKey);

        // 1. 确保信用档案已初始化并加行级排他锁 (FOR UPDATE)，串行化同一用户的并发信用变动
        getOrCreateCredit(userId);
        UserCredit credit = userCreditMapper.selectByUserIdForUpdate(userId);
        if (credit == null) {
            credit = getOrCreateCredit(userId);
        }

        // 2. 幂等校验：同一业务动作重复提交时短路返回，并留下可观测日志（绝不静默丢弃）
        Long existedLogs = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, userId)
                        .eq(UserCreditLog::getIdemKey, idemKey)
        );
        if (existedLogs != null && existedLogs > 0) {
            log.warn("[CREDIT-IDEMPOTENT-BLOCK] 命中幂等键，本次信用变动未生效（同一业务动作的重复请求）: "
                            + "userId={}, idemKey={}, changeType={}, relatedType={}, relatedId={}, actionKey={}, "
                            + "requestedScore={}, currentScore={}",
                    userId, idemKey, changeTypeCode, relatedType, relatedId, actionKey,
                    requestedScore, credit.getCreditScore());
            return credit;
        }

        // 3. 基于锁内权威数据计算前后分数、实际生效值与信用等级
        int beforeScore = credit.getCreditScore() != null ? credit.getCreditScore() : SCORE_DEFAULT;
        int rawScore = beforeScore + requestedScore;
        int afterScore = Math.min(SCORE_MAX, Math.max(SCORE_MIN, rawScore));
        // 实际生效值：被区间截断时小于请求值（完全截断时为 0），从而保证对账恒等式成立
        int actualDelta = afterScore - beforeScore;
        CreditLevel newLevel = CreditLevel.fromScore(afterScore);

        if (actualDelta != requestedScore) {
            log.warn("信用分触及 [{} , {}] 区间边界，请求值与实际生效值不一致: userId={}, requestedScore={}, actualDelta={}, before={}, after={}, changeType={}",
                    SCORE_MIN, SCORE_MAX, userId, requestedScore, actualDelta, beforeScore, afterScore, changeTypeCode);
        }

        LocalDateTime now = LocalDateTime.now();

        // 4. 原子更新用户信用实体（只写入实际生效值，数据库行锁保证无更新丢失）
        long completedDelta = (type == CreditChangeType.TRADE_COMPLETED) ? 1L : 0L;
        long cancelDelta = (type == CreditChangeType.TRADE_CANCEL_PENALTY) ? 1L : 0L;
        int tradeDelta = (type == CreditChangeType.TRADE_COMPLETED) ? 1 : 0;
        int goodReviewDelta = (type == CreditChangeType.REVIEW_GOOD) ? 1 : 0;
        int badReviewDelta = (type == CreditChangeType.REVIEW_BAD) ? 1 : 0;

        userCreditMapper.applyCreditAdjustment(
                userId,
                actualDelta,
                newLevel.name(),
                completedDelta,
                cancelDelta,
                tradeDelta,
                goodReviewDelta,
                badReviewDelta
        );

        credit.setCreditScore(afterScore);
        credit.setCreditLevel(newLevel.name());
        credit.setCompletedCount((credit.getCompletedCount() != null ? credit.getCompletedCount() : 0L) + completedDelta);
        credit.setCancelCount((credit.getCancelCount() != null ? credit.getCancelCount() : 0L) + cancelDelta);
        credit.setTradeCount((credit.getTradeCount() != null ? credit.getTradeCount() : 0) + tradeDelta);
        credit.setGoodReviewCount((credit.getGoodReviewCount() != null ? credit.getGoodReviewCount() : 0) + goodReviewDelta);
        credit.setBadReviewCount((credit.getBadReviewCount() != null ? credit.getBadReviewCount() : 0) + badReviewDelta);
        credit.setUpdatedTime(now);

        // 5. 生成审计流水：change_score = 实际生效值，request_score = 原始请求值
        UserCreditLog creditLog = UserCreditLog.builder()
                .userId(userId)
                .changeType(changeTypeCode)
                .changeScore(actualDelta)
                .requestScore(requestedScore)
                .idemKey(idemKey)
                .beforeScore(beforeScore)
                .afterScore(afterScore)
                .relatedType(relatedType)
                .relatedId(relatedId)
                .reason(reason)
                .createdTime(now)
                .build();

        try {
            userCreditLogMapper.insert(creditLog);
        } catch (DuplicateKeyException e) {
            // 理论上被用户级行锁挡在门外；此处兜底并显式记录，避免"静默回退"
            log.warn("[CREDIT-IDEMPOTENT-RACE] 审计流水被唯一索引 uk_credit_log_idempotent 拦截（并发重复业务动作）: "
                            + "userId={}, idemKey={}, changeType={}, relatedType={}, relatedId={}, actionKey={}",
                    userId, idemKey, changeTypeCode, relatedType, relatedId, actionKey, e);
            throw e;
        }

        log.info("信用积分变动成功: userId={}, changeType={}, requestedScore={}, actualDelta={}, before={}, after={}, level={}, idemKey={}",
                userId, changeTypeCode, requestedScore, actualDelta, beforeScore, afterScore, newLevel.name(), idemKey);

        return credit;
    }

    private UserCredit selectByUserId(Long userId) {
        return userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId)
        );
    }

    /**
     * 构造幂等键：{@code changeType|relatedType|relatedId|actionKey}。
     *
     * <p>空值统一折叠为分隔符之间的空串，保证键是稳定且可比较的字符串
     * （与 V10 迁移中历史流水回填表达式完全一致：
     * {@code change_type || '|' || COALESCE(related_type,'') || '|' || COALESCE(related_id::text,'') || '|'}）。</p>
     */
    static String buildIdemKey(String changeType, String relatedType, Long relatedId, String actionKey) {
        return changeType
                + IDEM_KEY_SEPARATOR + (relatedType == null ? "" : relatedType)
                + IDEM_KEY_SEPARATOR + (relatedId == null ? "" : relatedId.toString())
                + IDEM_KEY_SEPARATOR + (actionKey == null ? "" : actionKey);
    }
}
