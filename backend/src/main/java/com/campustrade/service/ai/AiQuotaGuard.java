package com.campustrade.service.ai;

import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.limit.RedisRateLimiter;
import com.campustrade.config.AiQuotaProperties;
import com.campustrade.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 接口调用配额守卫（按 userId 计"分钟频控 + 日配额"）。
 *
 * <h2>两个维度，缺一不可</h2>
 * <ul>
 *   <li><b>分钟频控</b>（默认 10 次/分钟）：拦住脚本化瞬时爆发，避免几十个并发请求同时打到 DeepSeek
 *       把连接池/额度一瞬间打满；</li>
 *   <li><b>日配额</b>（默认 50 次/天）：即使攻击者把请求摊平到一整天，单账号能消耗的额度也有确定上界，
 *       成本从"无限"收敛为"账号数 × 50 次"。非法批量账号的注册本身还受注册来源 IP 限频约束。</li>
 * </ul>
 *
 * <h2>Redis 不可用时 fail-closed</h2>
 * <p>本类保护的是"真金白银的外部 API 额度"，与登录失败计数那类"可用性优先"的软限流不同：
 * 计数依赖不可用时无法证明调用方仍在配额内，因此这里选择拒绝调用（HTTP 500 + 明确业务错误），
 * 而不是放行。日志会记录真实原因，便于运维区分"配额打满"与"Redis 故障"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQuotaGuard {

    private final RedisRateLimiter redisRateLimiter;
    private final AiQuotaProperties aiQuotaProperties;

    /**
     * 校验并占用一次 AI 调用配额；超限抛出 429 业务异常。
     *
     * @param userId 当前登录用户 ID
     */
    public void enforce(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录后再使用 AI 助手");
        }

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long epochMinute = System.currentTimeMillis() / 60000L;

        try {
            // 1. 短时频控（先判定，避免瞬时并发把日配额打散到无意义的"刚好不超"）
            redisRateLimiter.enforce(
                    RedisKeyConstants.aiQuotaMinuteKey(userId, epochMinute),
                    60L,
                    aiQuotaProperties.getPerMinuteLimit(),
                    "ai-minute",
                    "userId=" + userId,
                    "AI 助手调用过于频繁，请稍后再试"
            );

            // 2. 日配额
            redisRateLimiter.enforce(
                    RedisKeyConstants.aiQuotaDayKey(userId, today),
                    24 * 3600L,
                    aiQuotaProperties.getDailyLimit(),
                    "ai-daily",
                    "userId=" + userId,
                    "AI 助手今日调用次数已达上限（" + aiQuotaProperties.getDailyLimit() + " 次/天），请明天再试"
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 配额计数依赖的 Redis 不可用，为保证外部 API 成本可控执行 fail-closed: userId={}", userId, e);
            throw new BusinessException(500, "AI 助手暂时不可用，请稍后再试");
        }
    }
}
