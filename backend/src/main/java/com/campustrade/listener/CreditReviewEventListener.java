package com.campustrade.listener;

import com.campustrade.common.constant.CreditRule;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.event.ReviewCreatedEvent;
import com.campustrade.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 评价创建领域事件监听器
 * 驱动信用领域进行积分变动与审计流水沉淀
 * 使用 AFTER_COMMIT 保证在主事务提交后再解耦处理，配合独立事务与重试机制消除偶发异常导致的积分漂移与静默丢失
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditReviewEventListener {

    private final CreditService creditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handleReviewCreated(ReviewCreatedEvent event) {
        if (event == null || event.getReviewId() == null || event.getReviewedUserId() == null || event.getScore() == null) {
            log.warn("收到不合法的评价创建事件: {}", event);
            return;
        }

        Long targetUserId = event.getReviewedUserId();
        Long reviewId = event.getReviewId();
        int score = event.getScore();

        log.info("监听到评价创建事件，开始驱动信用联动: reviewId={}, targetUserId={}, score={}",
                reviewId, targetUserId, score);

        int maxAttempts = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                applyCreditChange(targetUserId, reviewId, score);
                log.info("评价驱动信用变动成功: reviewId={}, targetUserId={}, score={}, attempt={}",
                        reviewId, targetUserId, score, attempt);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("评价驱动信用变动第 {} 次执行失败: reviewId={}, targetUserId={}, error={}",
                        attempt, reviewId, targetUserId, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(100L * (1L << (attempt - 1))); // 100ms, 200ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("[CRITICAL_DATA_INCONSISTENCY] 评价驱动信用变动重试 {} 次后最终失败，需人工介入补偿: reviewId={}, targetUserId={}, score={}, error={}",
                maxAttempts, reviewId, targetUserId, score,
                lastException != null ? lastException.getMessage() : "unknown",
                lastException);
    }

    /**
     * 把一次评价星级折算为被评价人的信用变动。
     *
     * <p>变动幅度与方向全部来自 {@link CreditRule}：本方法不再复述"5星+3、1星-5"这类数值，
     * 也不再写加/扣两条分支（由 {@link CreditService#applyDelta} 按符号落地），
     * 从而与管理员"屏蔽冲正/恢复补偿"两条路径共用同一张星级分值表。</p>
     */
    private void applyCreditChange(Long targetUserId, Long reviewId, int score) {
        if (!CreditRule.isKnownStar(score)) {
            log.warn("未识别的星级评分，本次不做任何信用变动: score={}, reviewId={}", score, reviewId);
            return;
        }

        int delta = CreditRule.reviewDeltaForScore(score);
        if (delta == 0) {
            // 3 星为中性评价：规则值就是 0，明确记录"走过规则但无需调整"而不是静默跳过
            log.info("{}星评价为中性评价，不调整信用积分: reviewId={}, targetUserId={}", score, reviewId, targetUserId);
            return;
        }

        creditService.applyDelta(
                targetUserId,
                delta,
                delta > 0 ? CreditChangeType.REVIEW_GOOD : CreditChangeType.REVIEW_BAD,
                "REVIEW",
                reviewId,
                String.format("获得%d星交易%s", score, delta > 0 ? "好评" : (score == 1 ? "极差评" : "差评")),
                null
        );
    }
}
