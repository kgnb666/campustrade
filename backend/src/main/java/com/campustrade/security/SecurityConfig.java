package com.campustrade.security;

import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
import java.util.List;

/**
 * Spring Security 6 核心安全配置
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

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
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
