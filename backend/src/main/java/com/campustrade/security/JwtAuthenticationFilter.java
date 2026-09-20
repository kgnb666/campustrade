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
 *
 * <p><b>安全基线</b>：</p>
 * <ol>
 *   <li>令牌只用于证明"你是谁"，<b>角色与启用状态一律以数据库为准</b>；</li>
 *   <li>令牌签名有效但数据库中查不到该用户（已注销/被删除）时直接 401，
 *       绝不使用令牌内携带的角色兜底，也不默认放行；</li>
 *   <li>日志只输出令牌摘要指纹，绝不打印令牌明文。</li>
 * </ol>
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
            // 日志一律只输出令牌摘要指纹（SHA-256 前 8 位），绝不打印令牌明文
            String tokenFingerprint = TokenHashUtils.fingerprint(token);

            // 1. 检查 Token 是否已被列入 Redis 黑名单
            Boolean isBlacklisted = stringRedisTemplate.hasKey(BLACKLIST_PREFIX + token);
            if (Boolean.TRUE.equals(isBlacklisted)) {
                log.debug("令牌已被列入黑名单，拒绝认证: tokenFp={}", tokenFingerprint);
                filterChain.doFilter(request, response);
                return;
            }

            // 2. 校验 Token 有效性与类型 (必须为 ACCESS 令牌，杜绝 Refresh Token 混淆提权)
            if (jwtTokenProvider.validateToken(token)) {
                String tokenType = jwtTokenProvider.getTokenType(token);
                if (!"access".equalsIgnoreCase(tokenType)) {
                    log.warn("检测到非 ACCESS 令牌尝试访问受保护接口，拒绝认证: tokenType={}, tokenFp={}",
                            tokenType, tokenFingerprint);
                    filterChain.doFilter(request, response);
                    return;
                }

                String username = jwtTokenProvider.getUsername(token);

                // 3. 凭据一律以数据库为准：用户不存在（已注销/被删除）时直接 401，
                //    绝不用令牌内的角色兜底，也不默认 enabled=true
                UserDetails userDetails;
                try {
                    userDetails = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException ex) {
                    userDetails = null;
                }

                if (userDetails == null) {
                    log.warn("令牌持有者在数据库中不存在（可能已注销或已被删除），拒绝认证: username={}, tokenFp={}",
                            username, tokenFingerprint);
                    writeUnauthorized(response);
                    return;
                }

                // 唯一受支持的凭据载体是 UserPrincipal；出现其它实现说明装配被意外替换，一律按未认证处理
                if (!(userDetails instanceof UserPrincipal userPrincipal)) {
                    log.warn("UserDetails 实现非 UserPrincipal，拒绝认证: username={}, type={}",
                            username, userDetails.getClass().getName());
                    writeUnauthorized(response);
                    return;
                }

                // SEC-01 修复：账户启用状态来自数据库（FROZEN/BANNED 等一律 403）
                if (!userPrincipal.isEnabled()) {
                    log.warn("用户账号已被冻结或禁用，拒绝访问受保护接口: username={}", username);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    Result<Void> errorResult = Result.error(ResultCode.FORBIDDEN.getCode(), "账号已被冻结或禁用，请联系平台管理员");
                    response.getWriter().write(objectMapper.writeValueAsString(errorResult));
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 以统一 JSON 错误格式返回 401（与 {@code SecurityConfig#authenticationEntryPoint} 完全一致）。
     *
     * <p>用于"令牌签名有效，但凭据无法在数据库中落地"的场景：用户不存在、已注销或被删除。
     * 此时既不建立认证上下文，也不继续走到业务层，避免出现"幽灵用户"以令牌内角色访问接口。</p>
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> errorResult = Result.error(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已失效，请重新登录");
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
