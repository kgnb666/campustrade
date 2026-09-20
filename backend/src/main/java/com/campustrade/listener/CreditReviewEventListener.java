package com.campustrade.listener;

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

    private void applyCreditChange(Long targetUserId, Long reviewId, int score) {
        switch (score) {
            case 5:
                creditService.addCredit(
                        targetUserId,
                        3,
                        CreditChangeType.REVIEW_GOOD,
                        "REVIEW",
                        reviewId,
                        "获得5星交易好评"
                );
                break;
            case 4:
                creditService.addCredit(
                        targetUserId,
                        1,
                        CreditChangeType.REVIEW_GOOD,
                        "REVIEW",
                        reviewId,
                        "获得4星交易好评"
                );
                break;
            case 3:
                // 3星一般，不增减信用分
                log.info("3星评价，不调整信用积分: reviewId={}, targetUserId={}", reviewId, targetUserId);
                break;
            case 2:
                creditService.deductCredit(
                        targetUserId,
                        2,
                        CreditChangeType.REVIEW_BAD,
                        "REVIEW",
                        reviewId,
                        "获得2星交易差评"
                );
                break;
            case 1:
                creditService.deductCredit(
                        targetUserId,
                        5,
                        CreditChangeType.REVIEW_BAD,
                        "REVIEW",
                        reviewId,
                        "获得1星交易极差评"
                );
                break;
            default:
                log.warn("未识别的星级评分: score={}, reviewId={}", score, reviewId);
        }
    }
}
