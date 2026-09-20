package com.campustrade.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路标识（traceId）过滤器。
 *
 * <p>为每个请求生成/透传一个 traceId，写入：
 * <ul>
 *   <li>SLF4J MDC —— 日志 pattern 里的 {@code %X{traceId}} 会带上它，据此可把一次请求的所有日志串起来；</li>
 *   <li>响应头 {@code X-Trace-Id} —— 前端/测试反馈问题时可带上该值，便于服务端定位。</li>
 * </ul>
 *
 * <p>调用方可通过请求头 {@code X-Trace-Id} 传入自己的链路 ID（便于跨系统追踪）；
 * 非法或过长的值会被忽略并重新生成，避免污染日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    /** MDC 中的键名，同时也是日志 pattern 使用的键名。 */
    public static final String TRACE_ID = "traceId";

    /** 透传用的 HTTP 头名。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID, traceId);
        try {
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(TRACE_ID_HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            // 线程复用（Tomcat 线程池）时必须清理，否则会串到下一个请求
            MDC.remove(TRACE_ID);
        }
    }

    private String resolveTraceId(ServletRequest request) {
        if (request instanceof HttpServletRequest httpRequest) {
            String incoming = httpRequest.getHeader(TRACE_ID_HEADER);
            if (incoming != null) {
                String trimmed = incoming.trim();
                if (!trimmed.isEmpty() && trimmed.length() <= MAX_TRACE_ID_LENGTH
                        && trimmed.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-' || ch == '_')) {
                    return trimmed;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
