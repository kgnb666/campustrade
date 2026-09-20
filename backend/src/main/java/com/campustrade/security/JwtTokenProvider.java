package com.campustrade.security;

import io.jsonwebtoken.Claims;
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

/**
 * JWT Token 签发、校验与解析组件
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret:campustrade-super-secure-jwt-secret-key-2026-campus-trade-platform-xyz-token}")
    private String secret;

    @Value("${jwt.access-token-expiration:7200000}")
    private long accessTokenExpiration; // 默认 2 小时

    @Value("${jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration; // 默认 7 天

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Access Token (2小时)
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
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
     * 生成 Refresh Token (7天)
     */
    public String generateRefreshToken(Long userId, String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
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
     * 获取用户名
     */
    public String getUsername(String token) {
        return getClaims(token).getSubject();
    }

    /**
     * 获取用户 ID
     */
    public Long getUserId(String token) {
        Object val = getClaims(token).get("userId");
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return val != null ? Long.parseLong(val.toString()) : null;
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
