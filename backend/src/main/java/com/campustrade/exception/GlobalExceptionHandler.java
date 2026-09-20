package com.campustrade.exception;

import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常统一捕获处理器
 *
 * <h2>响应体不变，HTTP 状态码对齐语义</h2>
 * <p>所有分支的响应体始终是 {@code {code,message,data,timestamp}}（见 {@link Result}），
 * 不做任何字段增删。变化只发生在 HTTP 状态行：业务异常不再一律以 200 返回，
 * 而是按业务码映射为同名 HTTP 状态（映射表唯一存在于 {@link BusinessException#httpStatus()}），
 * 与鉴权层异常的 401/403、未匹配路径的 404 在传输层保持一致。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：HTTP 状态码由业务码映射而来，响应体结构保持不变。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        HttpStatus status = e.httpStatus();
        log.warn("业务异常: code={}, httpStatus={}, message={}", e.getCode(), status.value(), e.getMessage());
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 处理登录认证凭证错误
     */
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadCredentialsException(BadCredentialsException e) {
        log.warn("认证凭据错误: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
    }

    /**
     * 处理用户未找到
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUsernameNotFoundException(UsernameNotFoundException e) {
        log.warn("用户不存在: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), e.getMessage());
    }

    /**
     * 处理 Spring Security 权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("无权限访问: {}", e.getMessage());
        return Result.error(ResultCode.FORBIDDEN.getCode(), "权限不足，拒绝访问");
    }

    /**
     * 处理 Spring Security 未认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthenticationException(AuthenticationException e) {
        log.warn("未认证: {}", e.getMessage());
        return Result.error(ResultCode.UNAUTHORIZED.getCode(), "未登录或认证失败");
    }

    /**
     * 处理方法参数校验异常 (JSON RequestBody)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验异常: {}", errorMessage);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), errorMessage);
    }

    /**
     * 处理表单绑定异常 (FormData)
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String errorMessage = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("表单绑定异常: {}", errorMessage);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), errorMessage);
    }

    /**
     * 处理单个参数校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("字段校验异常: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), e.getMessage());
    }

    /**
     * 处理数据完整性与重复键冲突异常 (如唯一索引约束)
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDataIntegrityViolationException(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("数据完整性/冲突异常: {}", e.getMessage());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), "数据操作冲突或记录已存在");
    }

    /**
     * 处理方法参数类型不匹配异常 (例如路径参数 /favorite/abc)
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentTypeMismatchException(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return Result.error(ResultCode.BAD_REQUEST.getCode(), "参数类型错误: " + e.getName());
    }

    /**
     * 处理不支持的 HTTP 请求方法
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleHttpRequestMethodNotSupportedException(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {}", e.getMethod());
        return Result.error(HttpStatus.METHOD_NOT_ALLOWED.value(), "不支持的请求方式: " + e.getMethod());
    }

    /**
     * 处理请求体解析失败
     *
     * 常见场景：客户端（curl / Postman / 终端）用 GBK 等非 UTF-8 编码提交了含中文的 JSON。
     * 此前会落到兜底的 Exception 处理分支返回 500「系统繁忙」，把"编码不对"伪装成服务端故障，
     * 排查时极易误判，这里改为明确的 400 并直接点出编码问题。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleHttpMessageNotReadableException(org.springframework.http.converter.HttpMessageNotReadableException e) {
        Throwable root = e.getMostSpecificCause();
        String rootMessage = root == null ? null : root.getMessage();
        boolean encodingIssue = rootMessage != null
                && (rootMessage.contains("Invalid UTF-8")
                    || rootMessage.contains("Invalid UTF-16")
                    || rootMessage.contains("MalformedInputException"));
        String hint = encodingIssue
                ? "请求体不是合法的 UTF-8 编码，请以 UTF-8 提交 JSON（Windows 终端管道/curl 默认可能是 GBK）"
                : "请求体格式错误，请检查 JSON 是否合法";
        log.warn("请求体解析失败: {}", rootMessage == null ? e.getMessage() : rootMessage);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), hint);
    }

    /**
     * 处理未匹配到任何处理器/静态资源的请求路径（404）。
     *
     * <p>{@code NoResourceFoundException} 由 Spring 6.1 的静态资源处理器在"路径没有任何映射"
     * 时抛出（本项目 {@code spring.web.resources.add-mappings} 保持默认开启，因此未匹配路径
     * 走的是资源处理器而不是"无 handler"分支）。此前它会落到下面的兜底 {@code Exception} 分支，
     * 把"请求了一个不存在的接口"伪装成 500「系统繁忙」，误导排查方向。</p>
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFoundException(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("请求路径不存在: {}", e.getResourcePath());
        return Result.error(ResultCode.NOT_FOUND.getCode(), "请求的资源不存在: " + e.getResourcePath());
    }

    /**
     * 处理未匹配到任何处理器的请求路径（404）。
     *
     * <p>当 {@code spring.mvc.throw-exception-if-no-handler-found=true} 生效时，
     * 未匹配路径抛出的正是本异常（与上一条互为兜底，二者都必须存在）。</p>
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoHandlerFoundException(org.springframework.web.servlet.NoHandlerFoundException e) {
        log.warn("未找到处理器: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(ResultCode.NOT_FOUND.getCode(), "请求的资源不存在: " + e.getRequestURL());
    }

    /**
     * 处理未捕获的系统全局异常 (防止向前端泄露数据库敏感异常信息)
     *
     * <p>兜底分支只承接真正的服务端故障：{@code NoResourceFoundException} /
     * {@code NoHandlerFoundException} 已由上方的专用分支接管（Spring 按异常类型取最精确匹配，
     * 二者不会落到这里），因此"路径不存在"不会再被伪装成 500。</p>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统未知异常: ", e);
        return Result.error(ResultCode.INTERNAL_SERVER_ERROR.getCode(), "系统繁忙，请稍后重试");
    }
}
