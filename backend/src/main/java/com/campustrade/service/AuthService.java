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
     */
    Result<Void> register(RegisterRequestDTO dto);

    /**
     * 用户登录
     */
    Result<LoginVO> login(LoginRequestDTO dto);

    /**
     * 用户登出
     */
    Result<Void> logout(String bearerToken);

    /**
     * 刷新访问令牌 (使用 Refresh Token 续期 Access Token)
     */
    Result<TokenRefreshVO> refresh(RefreshTokenRequest request);
}

