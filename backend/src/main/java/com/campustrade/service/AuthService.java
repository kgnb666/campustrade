package com.campustrade.service;

import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.vo.LoginVO;
import com.campustrade.vo.TokenRefreshVO;

/**
 * 认证服务接口
 *
 * <h2>返回领域类型，不返回 Web 信封</h2>
 * <p>本接口与其它领域服务（订单、商品、评价等）保持一致：只返回领域类型或空，
 * 失败一律以业务异常表达；{@code Result} 信封由 Controller 统一包装。
 * 这样调用方拿到的就是可直接使用的对象，不需要 {@code .getData()} 解包，
 * 也不会出现"信封为错误时静默产出 null"这一类跨层污染。</p>
 */
public interface AuthService {

    /**
     * 用户注册
     *
     * @param dto      注册请求
     * @param clientIp 请求来源 IP（用于注册限频防护）
     */
    void register(RegisterRequestDTO dto, String clientIp);

    /**
     * 用户登录
     *
     * @param dto      登录请求
     * @param clientIp 请求来源 IP（用于登录失败计数与锁定防护）
     * @return 访问令牌、刷新令牌与当前用户资料（userInfo 恒非空）
     */
    LoginVO login(LoginRequestDTO dto, String clientIp);

    /**
     * 用户登出（Access Token 进黑名单 + 清除该用户的 Refresh Token 会话）
     */
    void logout(String bearerToken);

    /**
     * 刷新访问令牌 (使用 Refresh Token 续期 Access Token，并轮换 Refresh Token)
     */
    TokenRefreshVO refresh(RefreshTokenRequest request);
}
