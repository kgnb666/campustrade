package com.campustrade.security;

import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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
 *   <li><b>令牌内 userId 必须与数据库用户 id 一致</b>：只按令牌 {@code sub}（用户名）查库时，
 *       一旦将来加入"注销/彻底删除账号"，旧令牌会在同名账号被重建后认证成<b>另一个用户</b>。
 *       因此这里同时比对 {@code token.userId} 与数据库用户的 id，不一致即 401；</li>
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            // 日志一律只输出令牌摘要指纹（SHA-256 前 8 位），绝不打印令牌明文
            String tokenFingerprint = TokenHashUtils.fingerprint(token);

            // 1. 检查 Token 是否已被列入 Redis 黑名单
            Boolean isBlacklisted = stringRedisTemplate.hasKey(RedisKeyConstants.jwtBlacklistKey(token));
            if (Boolean.TRUE.equals(isBlacklisted)) {
                log.debug("令牌已被列入黑名单，拒绝认证: tokenFp={}", tokenFingerprint);
                filterChain.doFilter(request, response);
                return;
            }

            // 2. 一次性解析令牌载荷：签名合法且未过期是进入后续步骤的必要条件，
            //    这也是"用户 id / 用户名 / 令牌类型"的唯一来源。
            //    （原先 validateToken + getTokenType + getUsername 会把同一个令牌解析 3 次，
            //     每次都做一次 HMAC 验签；这里解析一次后从同一 Claims 取全部字段。）
            Claims claims;
            try {
                claims = jwtTokenProvider.getClaims(token);
            } catch (JwtException | IllegalArgumentException ex) {
                // 过期、签名非法、格式错误一律不建立认证上下文，交由后续入口点返回 401
                log.debug("令牌解析失败（已过期/签名非法/格式错误），拒绝认证: tokenFp={}, reason={}",
                        tokenFingerprint, ex.getMessage());
                filterChain.doFilter(request, response);
                return;
            }

            // 3. 令牌类型必须是 ACCESS，杜绝 Refresh Token 混淆提权
            Object rawTokenType = claims.get("type");
            String tokenType = rawTokenType == null ? null : rawTokenType.toString();
            if (!"access".equalsIgnoreCase(tokenType)) {
                log.warn("检测到非 ACCESS 令牌尝试访问受保护接口，拒绝认证: tokenType={}, tokenFp={}",
                        tokenType, tokenFingerprint);
                filterChain.doFilter(request, response);
                return;
            }

            String username = claims.getSubject();
            Long tokenUserId = jwtTokenProvider.getUserId(claims);

            // 4. 凭据一律以数据库为准：用户不存在（已注销/被删除）时直接 401，
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

            // 5. 令牌内 userId 必须与数据库用户 id 一致。
            //    仅凭用户名（sub）认证时，"账号被注销/删除后同名重建"会让旧令牌认证成新账号；
            //    比对失败即 401，日志给出双方 id 与令牌指纹以便定位，但不打印令牌原文。
            if (tokenUserId == null || !tokenUserId.equals(userPrincipal.getId())) {
                log.warn("令牌内 userId 与数据库用户不一致，拒绝认证: username={}, tokenUserId={}, dbUserId={}, tokenFp={}",
                        username, tokenUserId, userPrincipal.getId(), tokenFingerprint);
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
