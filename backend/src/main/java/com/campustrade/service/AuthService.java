package com.campustrade.service;

import com.campustrade.common.Result;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.vo.LoginVO;
import com.campustrade.vo.TokenRefreshVO;

/**
 * 认证服务接口
 */
public interface AuthService {

    /**
     * 用户注册
     *
     * @param dto      注册请求
     * @param clientIp 请求来源 IP（用于注册限频防护）
     */
    Result<Void> register(RegisterRequestDTO dto, String clientIp);

    /**
     * 用户登录
     *
     * @param dto      登录请求
     * @param clientIp 请求来源 IP（用于登录失败计数与锁定防护）
     */
    Result<LoginVO> login(LoginRequestDTO dto, String clientIp);

    /**
     * 用户登出（Access Token 进黑名单 + 清除该用户的 Refresh Token 会话）
     */
    Result<Void> logout(String bearerToken);

    /**
     * 刷新访问令牌 (使用 Refresh Token 续期 Access Token，并轮换 Refresh Token)
     */
    Result<TokenRefreshVO> refresh(RefreshTokenRequest request);
}

