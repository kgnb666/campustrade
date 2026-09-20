package com.campustrade.exception;

import com.campustrade.common.ResultCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Set;

/**
 * 自定义通用业务异常
 *
 * <h2>业务码 → HTTP 状态码的唯一映射点</h2>
 * <p>业务异常在响应体里始终以 {@code code} 表达语义（响应体结构保持
 * {@code {code,message,data,timestamp}} 不变），同时由 {@link #httpStatus()} 给出一个
 * <b>与之语义一致的 HTTP 状态码</b>，让"业务错误"与"鉴权错误"在传输层同样可判别
 * （此前业务异常一律以 HTTP 200 返回，只有 Spring Security 层异常是真 401/403，
 * 调用方必须解析响应体才能区分成功与失败）。</p>
 *
 * <p>只有下表列出的、本项目真实使用的业务码会映射为同名 HTTP 状态；其余取值（例如未来新增的
 * 自定义业务码，或 {@link #BusinessException(String)} 默认的 500）一律落到 500，
 * 避免把任意整数直接写进 HTTP 状态行。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 允许直接映射为 HTTP 状态码的业务码白名单（与项目真实使用的业务码一致）。
     */
    private static final Set<Integer> HTTP_STATUS_WHITELIST = Set.of(
            HttpStatus.BAD_REQUEST.value(),             // 400 参数/前置条件不满足
            HttpStatus.UNAUTHORIZED.value(),            // 401 未登录或凭据无效
            HttpStatus.FORBIDDEN.value(),               // 403 已登录但无权访问该资源
            HttpStatus.NOT_FOUND.value(),               // 404 目标资源不存在
            HttpStatus.METHOD_NOT_ALLOWED.value(),      // 405 请求方法不被支持
            HttpStatus.CONFLICT.value(),                // 409 并发/状态冲突，请刷新后重试
            HttpStatus.UNPROCESSABLE_ENTITY.value(),    // 422 状态机前置条件不满足（如订单未完成）
            HttpStatus.TOO_MANY_REQUESTS.value(),       // 429 触发限流或临时锁定
            HttpStatus.INTERNAL_SERVER_ERROR.value()    // 500 服务端自身故障
    );

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_SERVER_ERROR.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 当前业务码对应的 HTTP 状态码。
     *
     * @return 白名单内的业务码返回同名 HTTP 状态；其余一律返回 500
     */
    public HttpStatus httpStatus() {
        return HTTP_STATUS_WHITELIST.contains(code)
                ? HttpStatus.valueOf(code)
                : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
