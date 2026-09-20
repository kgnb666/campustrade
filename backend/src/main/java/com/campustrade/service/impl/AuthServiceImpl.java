package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtAuthenticationFilter;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AuthService;
import com.campustrade.service.UserService;
import com.campustrade.vo.LoginVO;
import com.campustrade.vo.TokenRefreshVO;
import com.campustrade.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 认证与授权业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserCreditMapper userCreditMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserService userService;

    public static final String REFRESH_TOKEN_PREFIX = RedisKeyConstants.JWT_REFRESH_PREFIX;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> register(RegisterRequestDTO dto) {
        // 1. 用户名重复校验
        Long usernameCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );
        if (usernameCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名已被占用，请更换其他用户名");
        }

        // 2. 邮箱重复校验
        Long emailCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, dto.getEmail())
        );
        if (emailCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该邮箱已被注册，请直接登录");
        }

        // 3. 构建用户对象并使用 BCrypt 加密密码
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(dto.getUsername())
                .password(passwordEncoder.encode(dto.getPassword()))
                .nickname(dto.getUsername()) // 默认昵称为用户名
                .email(dto.getEmail())
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();

        userMapper.insert(user);
        log.info("新用户注册成功: userId={}, username={}", user.getId(), user.getUsername());

        // 4. 自动生成用户初始信用档案 (credit_score = 100)
        UserCredit userCredit = UserCredit.builder()
                .userId(user.getId())
                .creditScore(100)
                .tradeCount(0)
                .goodReviewCount(0)
                .badReviewCount(0)
                .createdTime(now)
                .build();

        userCreditMapper.insert(userCredit);
        log.info("为新用户建立初始信用档案: userId={}, creditScore=100", user.getId());

        return Result.success("注册成功", null);
    }

    @Override
    public Result<LoginVO> login(LoginRequestDTO dto) {
        // 1. 根据用户名查找用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "该账号已被禁用，请联系平台管理员");
        }

        // 2. 生成 Access Token (2小时) 与 Refresh Token (7天)
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());

        // 3. 将 Refresh Token 存入 Redis 维护会话有效性 (TTL: 7天)
        stringRedisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                7,
                TimeUnit.DAYS
        );

        // 4. 查询当前用户基础信息与信用数据
        UserProfileVO profile = userService.getProfile(user.getUsername()).getData();

        LoginVO loginVO = LoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userInfo(profile)
                .build();

        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return Result.success("登录成功", loginVO);
    }

    @Override
    public Result<Void> logout(String bearerToken) {
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            String token = bearerToken.substring(7);
            long remainingMs = jwtTokenProvider.getRemainingExpiration(token);

            if (remainingMs > 0) {
                // 将未过期的 Token 写入 Redis 黑名单，TTL 为其剩余过期时间
                stringRedisTemplate.opsForValue().set(
                        JwtAuthenticationFilter.BLACKLIST_PREFIX + token,
                        "1",
                        remainingMs,
                        TimeUnit.MILLISECONDS
                );
                log.info("Token 已加入 Redis 黑名单，剩余有效时间: {} 毫秒", remainingMs);
            }
        }
        return Result.success("安全登出成功", null);
    }

    @Override
    public Result<TokenRefreshVO> refresh(RefreshTokenRequest request) {
        if (request == null || !StringUtils.hasText(request.getRefreshToken())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Refresh Token 不能为空");
        }

        String refreshToken = request.getRefreshToken().trim();

        // 1. 基础合法性与签名过期校验
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Refresh Token 无效或已过期，请重新登录");
        }

        // 2. Token 类型校验，必须为 refresh 令牌
        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"refresh".equalsIgnoreCase(tokenType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "无效的令牌类型，必须使用 Refresh Token 进行续期");
        }

        // 3. 用户身份与 Redis 白名单匹配校验
        Long userId = jwtTokenProvider.getUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "无效的令牌载荷信息");
        }

        String cachedRefreshToken = stringRedisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (cachedRefreshToken == null || !cachedRefreshToken.equals(refreshToken)) {
            log.warn("Refresh Token 白名单校验失败或已被置换: userId={}", userId);
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Refresh Token 已失效或已被其他设备置换，请重新登录");
        }

        // 4. 用户账号可用性校验
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "用户账号不存在或已被封禁");
        }

        // 5. 签发新的 Access Token (2小时) 并保留/返回 Refresh Token
        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());

        TokenRefreshVO vo = TokenRefreshVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .build();

        log.info("用户成功续期 Access Token: userId={}, username={}", user.getId(), user.getUsername());
        return Result.success("令牌刷新成功", vo);
    }
}

