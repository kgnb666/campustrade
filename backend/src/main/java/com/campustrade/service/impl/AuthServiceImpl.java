package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.limit.RedisRateLimiter;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.security.TokenHashUtils;
import com.campustrade.service.AuthService;
import com.campustrade.service.UserService;
import com.campustrade.vo.LoginVO;
import com.campustrade.vo.TokenRefreshVO;
import com.campustrade.vo.UserProfileVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import com.campustrade.common.constant.CreditRule;

/**
 * 认证与授权业务实现类
 *
 * <h2>会话与令牌安全设计</h2>
 * <ul>
 *   <li><b>Refresh Token 只存摘要</b>：Redis 中 {@code jwt:refresh:{userId}} 的值是令牌的
 *       SHA-256 摘要，缓存被读取也无法直接换取访问令牌；</li>
 *   <li><b>登出即失效</b>：登出同时把 Access Token 写入黑名单并删除该用户的 Refresh Token 会话；</li>
 *   <li><b>刷新即轮换</b>：每次成功刷新都会签发新的 Refresh Token 并覆盖旧摘要；
 *       旧令牌再次出现即视为重放，立刻清空该用户会话要求重新登录；</li>
 *   <li><b>登录防爆破</b>：按"用户名 + 来源 IP"双维度计数，连续失败达到阈值后临时锁定。</li>
 * </ul>
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
    private final RedisRateLimiter redisRateLimiter;

    /** 连续登录失败多少次后锁定（默认 5 次） */    @Value("${security.login.max-failures:5}")
    private int loginMaxFailures;

    /** 登录失败计数与锁定窗口时长（分钟），默认 15 分钟 */
    @Value("${security.login.lock-minutes:15}")
    private long loginLockMinutes;

    /** 同一来源 IP 每小时允许的注册请求上限（默认 20 次） */
    @Value("${security.register.ip-limit-per-hour:20}")
    private int registerIpLimitPerHour;

    /** /auth/refresh 与 /auth/logout 按来源 IP 的每分钟上限（默认 30 次） */
    @Value("${security.token-endpoint.ip-limit-per-minute:30}")
    private int tokenEndpointIpLimitPerMinute;

    /** /auth/refresh 与 /auth/logout 按令牌指纹的每分钟上限（默认 10 次） */
    @Value("${security.token-endpoint.token-limit-per-minute:10}")
    private int tokenEndpointTokenLimitPerMinute;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequestDTO dto, String clientIp) {
        // 0. 来源 IP 注册限频：阻断脚本化批量注册（每次请求都计数，命中上限直接拒绝）
        enforceRegisterIpLimit(clientIp);

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

        // 第 1、2 步的"先查后插"校验在并发注册（同一用户名/邮箱双击提交、脚本重放）下可能双双通过，
        // 此时由 user_username_key / user_email_key 唯一约束兜底。数据库唯一冲突必须翻译为
        // 400 业务语义（用户名/邮箱已被占用），而不是让它冒泡成 500。
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            String message = describeDuplicateRegistration(e, dto);
            log.warn("并发注册被唯一约束拦截: username={}, email={}, message={}", dto.getUsername(), dto.getEmail(), message);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), message);
        }
        log.info("新用户注册成功: userId={}, username={}", user.getId(), user.getUsername());

        // 4. 自动生成用户初始信用档案 (credit_score = CreditRule.SCORE_DEFAULT)
        //    单语句 INSERT ... ON CONFLICT (user_id) DO NOTHING：并发下不会抛唯一键异常污染事务。
        userCreditMapper.insertCreditIfAbsent(IdWorker.getId(), user.getId());
        log.info("为新用户建立初始信用档案: userId={}, creditScore={}", user.getId(), CreditRule.SCORE_DEFAULT);
    }

    @Override
    public LoginVO login(LoginRequestDTO dto, String clientIp) {
        // 计数键与数据库查询键分开：计数键做标准化（去首尾空白），避免用空白字符绕过限流
        String failUsernameKey = RedisKeyConstants.loginFailUsernameKey(
                dto.getUsername() == null ? "" : dto.getUsername().trim());
        String failIpKey = RedisKeyConstants.loginFailIpKey(clientIp);

        // 0. 锁定校验：达到阈值后直接拒绝，不再进行密码比对
        assertNotLocked(failUsernameKey, "登录失败次数过多，账号已被临时锁定，请 " + loginLockMinutes + " 分钟后再试");
        assertNotLocked(failIpKey, "当前网络登录失败次数过多，已被临时锁定，请 " + loginLockMinutes + " 分钟后再试");

        // 1. 根据用户名查找用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername())
        );

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 失败计数：用户名维度 + 来源 IP 维度，两个维度任一达到阈值即进入锁定
            recordLoginFailure(failUsernameKey, failIpKey);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "该账号已被禁用，请联系平台管理员");
        }

        // 2. 登录成功：清零失败计数，避免历史失败次数累积到误锁
        clearLoginFailures(failUsernameKey, failIpKey);

        // 3. 生成 Access Token (2小时) 与 Refresh Token (7天)
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());

        // 4. 会话有效性以 Redis 中的 Refresh Token 摘要为准 (TTL: 7天)
        saveRefreshSession(user.getId(), refreshToken);

        // 5. 查询当前用户基础信息与信用数据
        //    getProfile 是领域层调用：查不到用户会直接抛 404 业务异常，因此这里拿到的
        //    profile 要么是完整资料、要么是异常，绝不会出现"登录成功但 userInfo 为空"。
        UserProfileVO profile = userService.getProfile(user.getUsername());
        if (profile == null) {
            // 兜底不变量：getProfile 的契约是"非空或抛异常"，此处显式拦截异常实现，
            // 保证 LoginVO.userInfo 永不为 null（前端无需为"成功但无用户信息"补分支）。
            log.error("登录流程获取用户资料返回空，拒绝以空 userInfo 返回成功: userId={}, username={}",
                    user.getId(), user.getUsername());
            throw new BusinessException(ResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "登录失败：无法读取用户资料，请稍后重试");
        }

        LoginVO loginVO = LoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userInfo(profile)
                .build();

        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return loginVO;
    }

    @Override
    public void logout(String bearerToken, String clientIp) {
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            String token = bearerToken.substring(7);

            // 0. 限流：/auth/logout 未认证即可调用，且每次都要做 JWT 验签。
            //    按"真实来源 IP + 令牌指纹"双维度限制（令牌指纹只取摘要前 16 位，不存明文）。
            enforceTokenEndpointLimit("logout", clientIp, token);

            // 1. 单次解析令牌载荷：剩余过期时间与用户 ID 都从同一个 Claims 里取，
            //    不再为了两个字段把同一个令牌解析两遍。
            Claims claims = jwtTokenProvider.parseClaimsOrNull(token);
            if (claims == null) {
                log.warn("登出请求的令牌无法解析（签名非法或格式错误），跳过黑名单与会话清理");
                return;
            }

            long remainingMs = Math.max(claims.getExpiration().getTime() - System.currentTimeMillis(), 0);
            if (remainingMs > 0) {
                // 将未过期的 Token 写入 Redis 黑名单，TTL 为其剩余过期时间
                stringRedisTemplate.opsForValue().set(
                        RedisKeyConstants.jwtBlacklistKey(token),
                        "1",
                        remainingMs,
                        TimeUnit.MILLISECONDS
                );
                log.info("Access Token 已加入 Redis 黑名单，剩余有效时间: {} 毫秒", remainingMs);
            }

            // 删除该用户的 Refresh Token 会话：登出后刷新接口不再可用，无法"续命"出新令牌
            Long userId = jwtTokenProvider.getUserId(claims);
            if (userId != null) {
                Boolean removed = stringRedisTemplate.delete(RedisKeyConstants.jwtRefreshKey(userId));
                log.info("登出已清除 Refresh Token 会话: userId={}, removed={}", userId, removed);
            } else {
                log.warn("登出请求未能解析出用户身份，跳过 Refresh Token 会话清理（令牌可能非法或已被篡改）");
            }
        }
    }

    @Override
    public TokenRefreshVO refresh(RefreshTokenRequest request, String clientIp) {
        if (request == null || !StringUtils.hasText(request.getRefreshToken())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "Refresh Token 不能为空");
        }

        String refreshToken = request.getRefreshToken().trim();

        // 0. 限流：/auth/refresh 未认证即可调用，每次都要做 JWT 验签；
        //    按"真实来源 IP + 令牌指纹"双维度限制，避免用一个伪造令牌把签名校验 CPU 打满。
        enforceTokenEndpointLimit("refresh", clientIp, refreshToken);

        // 1. 单次解析：合法性、令牌类型、用户身份都来自同一个 Claims（原实现解析了 3 次）
        Claims claims = jwtTokenProvider.parseClaimsOrNull(refreshToken);
        if (claims == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Refresh Token 无效或已过期，请重新登录");
        }

        // 2. Token 类型校验，必须为 refresh 令牌
        Object tokenTypeClaim = claims.get("type");
        String tokenType = tokenTypeClaim == null ? null : tokenTypeClaim.toString();
        if (!"refresh".equalsIgnoreCase(tokenType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "无效的令牌类型，必须使用 Refresh Token 进行续期");
        }

        // 3. 用户身份与 Redis 会话摘要匹配校验（Redis 只保存摘要，比对的是摘要而非令牌明文）
        Long userId = jwtTokenProvider.getUserId(claims);
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "无效的令牌载荷信息");
        }

        String refreshKey = RedisKeyConstants.jwtRefreshKey(userId);
        String cachedHash = stringRedisTemplate.opsForValue().get(refreshKey);
        String presentedHash = TokenHashUtils.sha256Hex(refreshToken);

        if (cachedHash == null || !cachedHash.equals(presentedHash)) {
            if (cachedHash != null) {
                // 已轮换过的旧令牌被再次提交：视为重放/泄露信号，立即清空该用户会话
                stringRedisTemplate.delete(refreshKey);
                log.warn("检测到 Refresh Token 重放（摘要不匹配），已清空该用户会话: userId={}, presentedFp={}, cachedFp={}",
                        userId, TokenHashUtils.fingerprint(presentedHash), TokenHashUtils.fingerprint(cachedHash));
            } else {
                log.warn("Refresh Token 会话不存在（可能已登出或超时）: userId={}", userId);
            }
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Refresh Token 已失效或已被其他设备置换，请重新登录");
        }

        // 4. 用户账号可用性校验
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "用户账号不存在或已被封禁");
        }

        // 5. 刷新即轮换：签发新的 Access Token 与新的 Refresh Token，并用新摘要覆盖旧会话
        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        saveRefreshSession(user.getId(), newRefreshToken);

        TokenRefreshVO vo = TokenRefreshVO.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();

        log.info("用户成功续期并轮换令牌: userId={}, username={}, tokenFp={}",
                user.getId(), user.getUsername(), TokenHashUtils.fingerprint(newRefreshToken));
        return vo;
    }

    // =========================================================================
    // 内部防护与 Redis 辅助方法
    // =========================================================================

    /**
     * 把 PostgreSQL 唯一约束冲突翻译为"用户可读且指向真实原因"的业务提示。
     *
     * <p>注册表上有两个唯一约束：{@code user_username_key} 与 {@code user_email_key}，
     * 二者的冲突原因不同（占用用户名 / 占用邮箱），不能笼统回复"数据冲突"。</p>
     */
    private String describeDuplicateRegistration(DuplicateKeyException e, RegisterRequestDTO dto) {
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        String safeDetail = detail == null ? "" : detail.toLowerCase();
        if (safeDetail.contains("user_email_key") || safeDetail.contains("email")) {
            return "该邮箱已被注册，请直接登录";
        }
        if (safeDetail.contains("user_username_key") || safeDetail.contains("username")) {
            return "用户名已被占用，请更换其他用户名";
        }
        log.warn("注册唯一约束冲突原因无法归类，按用户名占用处理: username={}, email={}", dto.getUsername(), dto.getEmail());
        return "用户名或邮箱已被占用，请更换后重试";
    }

    /**
     * 写入/覆盖 Refresh Token 会话：Redis 中只保存令牌的 SHA-256 摘要，不保存令牌明文。
     */
    private void saveRefreshSession(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstants.jwtRefreshKey(userId),
                TokenHashUtils.sha256Hex(refreshToken),
                jwtTokenProvider.getRefreshTokenExpiration(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 注册来源 IP 限频：同一 IP 每个时间窗口（1 小时）内的注册请求次数上限。
     */
    private void enforceRegisterIpLimit(String clientIp) {
        String key = RedisKeyConstants.registerIpKey(clientIp);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, 1, TimeUnit.HOURS);
        }
        if (count != null && count > registerIpLimitPerHour) {
            log.warn("注册请求触发来源 IP 限频: ip={}, count={}, limit={}", clientIp, count, registerIpLimitPerHour);
            throw new BusinessException(429, "注册请求过于频繁，请稍后再试");
        }
    }

    /**
     * {@code /auth/refresh} 与 {@code /auth/logout} 的双维度限流。
     *
     * <p>这两个接口<b>未认证即可调用</b>，且每次调用都要做一次 JWT 验签（HMAC + Base64 解码），
     * 原先没有任何次数约束：攻击者可以用一个乱写的令牌把 CPU 打满（放大攻击成本极低），
     * 也可以拿泄露的 refresh token 做在线暴力尝试。这里按两个维度同时计数：</p>
     * <ul>
     *   <li><b>来源 IP</b>（默认 30 次/分钟）：限制单一来源的整体强度。IP 取自
     *       {@link com.campustrade.common.util.ClientIpUtils} 的可信代理解析，
     *       伪造 {@code X-Forwarded-For} 不再能绕过；</li>
     *   <li><b>令牌指纹</b>（默认 10 次/分钟）：限制"同一个令牌"被反复提交。
     *       指纹是令牌 SHA-256 摘要的前 16 位，键里不出现令牌明文。</li>
     * </ul>
     *
     * <p>日志只记录端点名、维度与计数，不打印 IP 与令牌指纹的组合明细。</p>
     *
     * @param endpoint  端点名（refresh / logout），用于拼键与日志
     * @param clientIp  真实来源 IP（可信代理解析结果）
     * @param rawToken  原始令牌（仅用于计算摘要，不落日志、不进键）
     */
    private void enforceTokenEndpointLimit(String endpoint, String clientIp, String rawToken) {
        String fingerprint = TokenHashUtils.rateLimitFingerprint(rawToken);
        boolean logout = "logout".equals(endpoint);

        redisRateLimiter.enforce(
                logout ? RedisKeyConstants.authLogoutIpKey(clientIp) : RedisKeyConstants.authRefreshIpKey(clientIp),
                60L,
                tokenEndpointIpLimitPerMinute,
                endpoint + "-ip",
                "ip=" + clientIp,
                "请求过于频繁，请稍后再试"
        );

        redisRateLimiter.enforce(
                logout ? RedisKeyConstants.authLogoutTokenKey(fingerprint) : RedisKeyConstants.authRefreshTokenKey(fingerprint),
                60L,
                tokenEndpointTokenLimitPerMinute,
                endpoint + "-token",
                "tokenFp=" + fingerprint,
                "该令牌请求过于频繁，请稍后再试"
        );
    }

    /**
     * 判断给定计数键是否已达锁定阈值。
     */
    private void assertNotLocked(String key, String lockMessage) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(value)) {
            return;
        }

        int failureCount;
        try {
            failureCount = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            // 计数键内容异常（被人工改写等），清理后按未锁定处理，避免把用户永久挡在门外
            log.warn("登录失败计数键内容非法，已重置: key={}, value={}", key, value);
            stringRedisTemplate.delete(key);
            return;
        }

        if (failureCount >= loginMaxFailures) {
            throw new BusinessException(429, lockMessage);
        }
    }

    /**
     * 记录一次登录失败。计数窗口采用滑动窗口（每次失败刷新 TTL），阈值由配置决定。
     */
    private void recordLoginFailure(String failUsernameKey, String failIpKey) {
        incrementWithTtl(failUsernameKey);
        incrementWithTtl(failIpKey);
    }

    private void incrementWithTtl(String key) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, loginLockMinutes, TimeUnit.MINUTES);
        if (count != null && count >= loginMaxFailures) {
            log.warn("登录失败次数达到锁定阈值: key={}, count={}, lockMinutes={}", key, count, loginLockMinutes);
        }
    }

    /**
     * 登录成功后清零失败计数。
     */
    private void clearLoginFailures(String failUsernameKey, String failIpKey) {
        stringRedisTemplate.delete(failUsernameKey);
        stringRedisTemplate.delete(failIpKey);
    }
}

