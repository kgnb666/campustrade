package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.common.util.ClientIpUtils;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.service.AuthService;
import com.campustrade.vo.LoginVO;
import com.campustrade.vo.TokenRefreshVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 (注册、登录、登出、令牌刷新)
 *
 * <p>来源 IP 一律经 {@link ClientIpUtils} 解析：只有 {@code remoteAddr} 落在
 * {@code security.trusted-proxies} 配置的代理网段内时才会采信 {@code X-Forwarded-For}，
 * 否则一律使用 TCP 对端地址（默认配置就是这样，伪造转发头不会改变限流维度）。</p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpUtils clientIpUtils;

    /**
     * 用户注册接口（按来源 IP 限频）
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequestDTO dto, HttpServletRequest request) {
        authService.register(dto, clientIpUtils.resolve(request));
        return Result.success("注册成功", null);
    }

    /**
     * 用户登录接口（按用户名与来源 IP 记录失败次数，超阈值临时锁定）
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequestDTO dto, HttpServletRequest request) {
        return Result.success("登录成功", authService.login(dto, clientIpUtils.resolve(request)));
    }

    /**
     * 用户登出接口（按来源 IP + 令牌指纹限流）
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String bearerToken,
                              HttpServletRequest request) {
        authService.logout(bearerToken, clientIpUtils.resolve(request));
        return Result.success("安全登出成功", null);
    }

    /**
     * 访问令牌刷新接口 (使用 Refresh Token 续期 Access Token，按来源 IP + 令牌指纹限流)
     */
    @PostMapping("/refresh")
    public Result<TokenRefreshVO> refresh(@Valid @RequestBody RefreshTokenRequest dto, HttpServletRequest request) {
        return Result.success("令牌刷新成功", authService.refresh(dto, clientIpUtils.resolve(request)));
    }
}
