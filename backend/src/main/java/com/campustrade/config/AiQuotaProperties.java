package com.campustrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 商品助手调用配额配置（{@code ai.quota.*}）。
 *
 * <h2>为什么需要配额</h2>
 * <p>{@code /ai/**} 三个接口只要求"已登录"，没有任何次数约束：一个账号（或批量注册的账号）
 * 可以对 DeepSeek 发起无限次调用。这里按 userId 计两个维度的配额：</p>
 * <ul>
 *   <li>{@code per-minute-limit}：短时频控，拦住脚本化的瞬时爆发；</li>
 *   <li>{@code daily-limit}：日配额，把"单个账号一天能烧掉多少额度"收敛为确定上界。</li>
 * </ul>
 * <p>两个维度都只在 Redis 里计数（键与 TTL 见 {@code RedisKeyConstants}），不落库。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.quota")
public class AiQuotaProperties {

    /** 单个用户每日允许的 AI 调用次数上限（默认 50 次/天）。 */
    private int dailyLimit = 50;

    /** 单个用户每分钟允许的 AI 调用次数上限（默认 10 次/分钟）。 */
    private int perMinuteLimit = 10;
}
