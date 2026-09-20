package com.campustrade.common.limit;

import com.campustrade.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 计数型限流器（"先 INCR 再判定"）。
 *
 * <h2>为什么必须先 INCR，而不是"先 GET 判断再 INCR"</h2>
 * <p>先读后写在并发下必然超发：两个请求同时读到 {@code count=9}（上限 10），都会认为"还没到上限"，
 * 然后各自放行并把计数加一，实际放行 11 次。把计数动作放到判定之前，每个请求都会先原子地占用一个配额，
 * 只有真正拿到 {@code count <= limit} 的那个请求继续执行，超出的请求在业务动作之前就被拒绝。</p>
 *
 * <h2>为什么每次都刷新 TTL</h2>
 * <p>{@code INCR} 与 {@code EXPIRE} 是两条命令。如果只在 {@code count == 1} 时设置 TTL，
 * 一旦设置失败（或进程在两条命令之间被 kill），键就会变成"没有过期时间"的永久键，
 * 把该用户永久卡在限流状态里（需要人工去 Redis 删键才能恢复）。
 * 这里每次计数都无条件刷新 TTL：代价是"持续请求会把窗口尾部一起拖长"（滑动窗口语义），
 * 换来的是"键一定会在有限时间内消失"，不会留下永久卡死的计数。</p>
 *
 * <p>本类只负责计数与拒绝，不含任何业务判断；Redis 不可用时由调用方决定 fail-closed
 * 还是降级（例如 {@code ReportServiceImpl} 会退化为按数据库当日计数兜底）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 占用一个配额并返回占用后的计数。
     *
     * @param key       计数键
     * @param ttlSeconds 计数键的存活时间（每次调用都会刷新）
     * @return 自增后的计数（Redis 返回值异常时按 1 处理，宁可放行也不误锁）
     */
    public long increment(String key, long ttlSeconds) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        return count != null ? count : 1L;
    }

    /**
     * 占用一个配额；超过上限则抛出 429 业务异常。
     *
     * @param key        计数键
     * @param ttlSeconds 计数键存活时间
     * @param limit      窗口内允许的最大次数
     * @param dimension  限流维度（仅用于日志，不打印敏感键值）
     * @param identity   被限流主体的可读标识（仅用于日志）
     * @param message    超限时返回给调用方的业务提示
     * @return 自增后的计数
     */
    public long enforce(String key, long ttlSeconds, long limit,
                        String dimension, String identity, String message) {
        long count = increment(key, ttlSeconds);
        if (count > limit) {
            log.warn("触发限流: dimension={}, identity={}, count={}, limit={}, windowSeconds={}",
                    dimension, identity, count, limit, ttlSeconds);
            throw new BusinessException(429, message);
        }
        return count;
    }
}
