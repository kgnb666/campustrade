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
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册接口（按来源 IP 限频）
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequestDTO dto, HttpServletRequest request) {
        return authService.register(dto, ClientIpUtils.resolve(request));
    }

    /**
     * 用户登录接口（按用户名与来源 IP 记录失败次数，超阈值临时锁定）
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequestDTO dto, HttpServletRequest request) {
        return authService.login(dto, ClientIpUtils.resolve(request));
    }

    /**
     * 用户登出接口
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String bearerToken) {
        return authService.logout(bearerToken);
    }

    /**
     * 访问令牌刷新接口 (使用 Refresh Token 续期 Access Token)
     */
    @PostMapping("/refresh")
    public Result<TokenRefreshVO> refresh(@Valid @RequestBody RefreshTokenRequest dto) {
        return authService.refresh(dto);
    }
}
