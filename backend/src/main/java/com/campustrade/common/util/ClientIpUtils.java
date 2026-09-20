package com.campustrade.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 客户端真实 IP 解析工具类
 *
 * <p>用于认证防护（登录失败计数、注册限频）按来源 IP 维度限流。
 * 解析顺序：{@code X-Forwarded-For}（取第一段）→ {@code X-Real-IP} → {@code remoteAddr}。</p>
 *
 * <p>注意：代理头可被客户端伪造，因此该值只用于"提高攻击成本"的软限流，
 * 权威的身份判定仍然只依赖数据库中的用户记录与令牌签名。</p>
 */
public final class ClientIpUtils {

    /** 无法解析来源时的兜底标识，避免出现 null 键或空键。 */
    public static final String UNKNOWN_IP = "unknown";

    private ClientIpUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 解析请求来源 IP。
     *
     * @param request 当前请求，允许为 null（如内部调用/测试）
     * @return 来源 IP，无法解析时返回 {@link #UNKNOWN_IP}
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (StringUtils.hasText(ip) && ip.contains(",")) {
            // 多级代理时取最左侧的原始客户端地址
            ip = ip.split(",")[0].trim();
        }
        return StringUtils.hasText(ip) ? ip : UNKNOWN_IP;
    }
}
