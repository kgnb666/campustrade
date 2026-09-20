package com.campustrade.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT Token 签发、校验与解析组件
 *
 * <p><b>密钥来源</b>：只从配置项 {@code jwt.secret}（即环境变量 {@code JWT_SECRET}）读取，
 * 源码与配置文件中不再保留任何可用默认密钥。启动时执行 fail-fast 校验，
 * 任何不满足要求的密钥都会让应用拒绝启动，而不是以弱密钥继续对外提供认证服务。</p>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * 密钥最小字节数：HMAC-SHA256 的密钥长度下限（32 字节 = 256 位）。
     * jjwt 的 {@code Keys.hmacShaKeyFor} 同样要求 ≥ 32 字节，这里提前给出可读的错误提示。
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 历史默认密钥的 SHA-256 摘要（该密钥曾硬编码在 application.yml 与源码 fallback 中，
     * 必须视为已泄露的公开值）。此处只保存摘要而非明文：既能拒绝该密钥继续被使用，
     * 又不会让已废弃的凭据字面量留在代码库中被安全扫描器判为硬编码凭据。
     */
    private static final String LEGACY_DEFAULT_SECRET_SHA256 =
            "0b0b20511b17f1840974fa267b96faed7099fab867c316abffe2a1e26458014b";

    /**
     * JWT 密钥。无默认值：缺失时为空串，由 {@link #init()} 统一给出明确中文提示并拒绝启动。
     */
    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.access-token-expiration:7200000}")
    private long accessTokenExpiration; // 默认 2 小时

    @Value("${jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration; // 默认 7 天

    private SecretKey key;

    /**
     * 启动期密钥校验与密钥材料构建（fail-fast）。
     *
     * <p>三种情况一律拒绝启动：密钥缺失/空白、长度不足 32 字节、命中历史默认密钥。
     * 提示语直接给出可照做的解决办法，避免运维面对 "Could not resolve placeholder"
     * 之类的间接报错去猜原因。</p>
     */
    @PostConstruct
    public void init() {
        // 去掉环境变量注入时可能夹带的换行与首尾空白（.env 解析、CI 注入都可能引入）
        String normalized = secret == null ? null : secret.trim();

        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalStateException(
                    "JWT 密钥缺失：认证服务拒绝启动。请通过环境变量 JWT_SECRET 提供密钥（至少 "
                            + MIN_SECRET_BYTES + " 字节），例如执行 openssl rand -hex 32 生成；"
                            + "本地开发可在项目根目录 .env 中配置 JWT_SECRET，由 backend/run-backend.cmd 自动注入");
        }

        byte[] keyBytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥强度不足：当前长度 " + keyBytes.length + " 字节，要求至少 " + MIN_SECRET_BYTES
                            + " 字节。请重新生成并写入环境变量 JWT_SECRET，例如执行 openssl rand -hex 32");
        }

        if (LEGACY_DEFAULT_SECRET_SHA256.equalsIgnoreCase(TokenHashUtils.sha256Hex(normalized))) {
            throw new IllegalStateException(
                    "JWT 密钥不安全：检测到正在使用已公开的历史默认密钥，该密钥必须视为已泄露。"
                            + "请重新生成 JWT_SECRET（例如执行 openssl rand -hex 32）后再启动");
        }

        this.key = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 密钥校验通过（来源: 环境变量/配置项 jwt.secret，长度: {} 字节）", keyBytes.length);
    }

    /**
     * 生成 Access Token (2小时)
     *
     * <p>显式写入随机 {@code jti}：JWT 的时间戳只精确到秒，同一秒内为同一用户签发的令牌
     * 若不携带唯一标识就会逐字节相同——这会让"刷新即轮换"退化为"原样返回同一个令牌"，
     * 也会让登出黑名单/日志指纹无法区分具体是哪一次签发。因此每次签发都带上随机 jti。</p>
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 生成 Refresh Token (7天)。
     *
     * <p>同样携带随机 {@code jti}：轮换必须真正产生"新令牌"，
     * 否则旧令牌仍然等于新令牌，重放检测与轮换都会失去意义。</p>
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("userId", userId)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    /**
     * 校验 Token 是否合法且未过期
     *
     * <p>语义等价于"{@link #getClaims(String)} 不抛异常"。请求链路上的
     * {@link JwtAuthenticationFilter} 需要在一次请求内同时拿到类型、用户名与 userId，
     * 因此它直接调用 {@link #getClaims(String)} 并自行处理异常（只验签一次），
     * 不再调用本方法；本方法保留给"只想知道真/假"的调用方与测试使用。</p>
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("无效的 JWT Token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 Claims
     */
    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 一次性解析令牌载荷，供"单次请求内只需要解析一次"的调用方使用。
     *
     * <p>背景：{@code /auth/refresh} 与 {@code /auth/logout} 原先分别调用
     * {@code validateToken} / {@code getTokenType} / {@code getUserId} /
     * {@code getRemainingExpiration}，同一个令牌在一次请求内会被解析 2~3 次
     * （每次都做一次 HMAC-Base64 验签）。这两个接口未认证即可调用，重复解析是纯浪费，
     * 也让放大攻击更划算。改为解析一次后从同一个 {@link Claims} 取所需字段。</p>
     *
     * <p>签名合法但<b>已过期</b>的令牌同样返回其载荷：登出需要在令牌刚过期时也能清理
     * Refresh 会话（否则用户"登不出去"）。签名非法/格式错误返回 {@code null}。</p>
     *
     * @param token 原始令牌
     * @return 载荷；无法安全解析时返回 null
     */
    public Claims parseClaimsOrNull(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return getClaims(token);
        } catch (ExpiredJwtException ex) {
            return ex.getClaims();
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT 解析失败（签名非法或格式错误）: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 从已解析的载荷中读取用户 ID（避免为取一个字段再解析一次令牌）。
     */
    public Long getUserId(Claims claims) {
        return claims == null ? null : extractUserId(claims);
    }

    /**
     * 获取用户名
     */
    public String getUsername(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * 获取用户 ID
     */
    public Long getUserId(String token) {
        return extractUserId(getClaims(token));
    }

    /**
     * 在令牌已过期的情况下仍尽力解析用户 ID，用于登出清理会话。
     *
     * <p>登出接口只需携带 Access Token：若该令牌恰好刚过期，按常规解析会抛
     * {@code ExpiredJwtException}，导致 Refresh Token 会话无法被清除（用户"登不出去"、
     * 刷新接口仍可换取新令牌）。这里对"签名有效但已过期"的令牌沿用其 Claims，
     * 从而把会话一并清理掉；签名非法或格式错误的令牌仍返回 null。</p>
     *
     * @return 用户 ID；无法安全解析时返回 null
     */
    public Long getUserIdAllowingExpired(String token) {
        try {
            return getUserId(token);
        } catch (ExpiredJwtException ex) {
            return extractUserId(ex.getClaims());
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    private Long extractUserId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object val = claims.get("userId");
        if (val instanceof Number number) {
            return number.longValue();
        }
        return val != null ? Long.parseLong(val.toString()) : null;
    }

    /**
     * Refresh Token 有效期（毫秒），用于设置 Redis 会话摘要的 TTL
     */
    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    /**
     * 获取用户角色 (USER, ADMIN 等)
     */
    public String getRole(String token) {
        try {
            Object role = getClaims(token).get("role");
            return role != null ? role.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 Token 类型 (access 或 refresh)
     */
    public String getTokenType(String token) {
        try {
            Object type = getClaims(token).get("type");
            return type != null ? type.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算 Token 剩余有效时间 (毫秒)，用于设置 Redis 黑名单 TTL
     */
    public long getRemainingExpiration(String token) {
        try {
            Date expiration = getClaims(token).getExpiration();
            long diff = expiration.getTime() - System.currentTimeMillis();
            return Math.max(diff, 0);
        } catch (Exception e) {
            return 0;
        }
    }
}
