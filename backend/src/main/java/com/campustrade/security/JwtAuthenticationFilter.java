package com.campustrade.security;

import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证拦截过滤器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public static final String BLACKLIST_PREFIX = RedisKeyConstants.JWT_BLACKLIST_PREFIX;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            // 1. 检查 Token 是否已被列入 Redis 黑名单
            Boolean isBlacklisted = stringRedisTemplate.hasKey(BLACKLIST_PREFIX + token);
            if (Boolean.TRUE.equals(isBlacklisted)) {
                log.debug("Token 已被列入黑名单，拒绝认证: {}", token);
                filterChain.doFilter(request, response);
                return;
            }

            // 2. 校验 Token 有效性与类型 (必须为 ACCESS 令牌，杜绝 Refresh Token 混淆提权)
            if (jwtTokenProvider.validateToken(token)) {
                String tokenType = jwtTokenProvider.getTokenType(token);
                if (!"access".equalsIgnoreCase(tokenType)) {
                    log.warn("检测到非 ACCESS 令牌尝试访问受保护接口，拒绝认证: tokenType={}", tokenType);
                    filterChain.doFilter(request, response);
                    return;
                }

                String username = jwtTokenProvider.getUsername(token);
                Long userId = jwtTokenProvider.getUserId(token);
                String role = jwtTokenProvider.getRole(token);

                UserDetails userDetails = null;
                try {
                    userDetails = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException ex) {
                    log.debug("UserDetailsService 未找到用户: username={}, 将基于 Token 凭据构建 Principal", username);
                }

                // SEC-01 修复：显式增加账户启用状态校验，被冻结/禁用用户直接拦截返回 403 统一错误响应
                if (userDetails != null && !userDetails.isEnabled()) {
                    log.warn("用户账号已被冻结或禁用，拒绝访问受保护接口: username={}", username);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    Result<Void> errorResult = Result.error(ResultCode.FORBIDDEN.getCode(), "账号已被冻结或禁用，请联系平台管理员");
                    response.getWriter().write(objectMapper.writeValueAsString(errorResult));
                    return;
                }

                UserPrincipal userPrincipal;
                if (userDetails instanceof UserPrincipal up) {
                    userPrincipal = up;
                    if (userPrincipal.getId() == null && userId != null) {
                        userPrincipal.setId(userId);
                    }
                    if (userPrincipal.getRole() == null && role != null) {
                        userPrincipal.setRole(role);
                    }
                } else {
                    userPrincipal = UserPrincipal.builder()
                            .id(userId)
                            .username(username)
                            .role(role != null ? role : "USER")
                            .enabled(userDetails != null ? userDetails.isEnabled() : true)
                            .build();
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
