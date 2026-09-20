package com.campustrade.security;

import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 6 核心安全配置
 *
 * <p>CORS 来源白名单由配置项 {@code cors.allowed-origins}（逗号分隔，支持
 * {@code http://localhost:*} 这类端口通配）决定，默认只放行本机开发地址。
 * 在 {@code allowCredentials=true} 的前提下，绝不允许无条件 {@code *}：
 * 那会让任意站点带着浏览器凭据访问本服务。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /**
     * 允许的跨域来源白名单（逗号分隔）。默认值覆盖本地开发常见的 localhost / 127.0.0.1 任意端口，
     * 生产环境请通过环境变量 {@code CORS_ALLOWED_ORIGINS} 覆盖为真实前端域名。
     */
    @Value("${cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF（无状态 REST API 场景）
                .csrf(AbstractHttpConfigurer::disable)
                // 启用标准 CORS 配置
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 基于 JWT，设置为无状态 Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 异常统一处理
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )
                // 接口访问权限控制
                .authorizeHttpRequests(auth -> auth
                        // 公开接口：注册、登录、高校列表、商品分类
                        .requestMatchers(
                                "/auth/**", "/api/auth/**",
                                "/school/**", "/api/school/**",
                                "/category/**", "/api/category/**"
                        ).permitAll()
                        // 公开 GET 接口：商品列表与商品搜索、热搜、商品详情
                        .requestMatchers(
                                org.springframework.http.HttpMethod.GET,
                                "/goods/list", "/api/goods/list",
                                "/goods/search", "/api/goods/search",
                                "/goods/search/hot", "/api/goods/search/hot",
                                "/goods/{id:[0-9]+}", "/api/goods/{id:[0-9]+}",
                                "/reviews/goods/**", "/api/reviews/goods/**"
                        ).permitAll()
                        // 需认证接口：个人信息、校园认证、文件上传、商品管理、收藏、历史足迹、AI助手
                        .requestMatchers(
                                "/user/**", "/api/user/**",
                                "/student/**", "/api/student/**",
                                "/file/**", "/api/file/**",
                                "/favorite/**", "/api/favorite/**",
                                "/history/**", "/api/history/**",
                                "/ai/**", "/api/ai/**",
                                "/goods/**", "/api/goods/**"
                        ).authenticated()
                        // 管理员专属治理接口：严格限制必须拥有 ROLE_ADMIN
                        .requestMatchers(
                                "/admin/**", "/api/admin/**"
                        ).hasRole("ADMIN")
                        // 其他请求默认需认证
                        .anyRequest().authenticated()
                )
                // 注册 JWT 过滤器在用户名密码过滤器之前
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 未认证（401 Unauthorized）JSON 统一响应
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            Result<Void> result = Result.error(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已失效，请重新登录");
            try (PrintWriter writer = response.getWriter()) {
                writer.write(objectMapper.writeValueAsString(result));
                writer.flush();
            }
        };
    }

    /**
     * 权限不足（403 Forbidden）JSON 统一响应
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            Result<Void> result = Result.error(ResultCode.FORBIDDEN.getCode(), "权限不足，拒绝访问");
            try (PrintWriter writer = response.getWriter()) {
                writer.write(objectMapper.writeValueAsString(result));
                writer.flush();
            }
        };
    }

    /**
     * CORS 跨域规则配置 Source
     *
     * <p>来源白名单来自配置项 {@code cors.allowed-origins}，逐项去空白后使用
     * {@code setAllowedOriginPatterns} 注册（支持 {@code http://localhost:*} 端口通配）。
     * {@code allowCredentials} 保持 true，但生效范围被严格限定在白名单之内。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        if (origins.isEmpty()) {
            // 白名单为空时不允许跨域，而不是退回无条件放行
            origins = List.of("http://localhost");
            log.warn("配置项 cors.allowed-origins 为空，跨域请求将仅允许 http://localhost");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
