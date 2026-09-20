package com.campustrade.security;

import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Spring MVC 自定义参数解析器：自动注入标注了 @CurrentUser 的 User 实体或 UserPrincipal
 * 优先从 SecurityContextHolder 的 UserPrincipal 快速解析与组装，实现零 DB-I/O，避免高频请求重复查库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentUserMethodArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserService userService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentUser.class)) {
            return false;
        }
        Class<?> paramType = parameter.getParameterType();
        return User.class.isAssignableFrom(paramType) || UserPrincipal.class.isAssignableFrom(paramType);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        boolean required = (annotation == null) || annotation.required();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            if (required) {
                throw new BusinessException(401, "用户未登录或登录态无效");
            }
            return null;
        }

        Object principal = authentication.getPrincipal();

        // 1. 快速通道：若 Principal 为 UserPrincipal，直接组装返回，完全绕过数据库查询
        if (principal instanceof UserPrincipal userPrincipal) {
            if (UserPrincipal.class.isAssignableFrom(parameter.getParameterType())) {
                return userPrincipal;
            }

            // 构造兼容既有业务代码签名的轻量 User 实体 (零 DB-I/O)
            User lightweightUser = User.builder()
                    .id(userPrincipal.getId())
                    .username(userPrincipal.getUsername())
                    .role(userPrincipal.getRole())
                    .status(userPrincipal.isEnabled() ? "ACTIVE" : "FROZEN")
                    .build();
            return lightweightUser;
        }

        // 2. 兜底回退：若 Principal 为其他类型（如普通 String），尝试通过 userService 查询完整实体
        String username = SecurityUtils.getCurrentUsernameOrNull();
        if (username == null) {
            if (required) {
                throw new BusinessException(401, "用户未登录或登录态无效");
            }
            return null;
        }

        User user = userService.getByUsername(username);
        if (user == null) {
            if (required) {
                throw new BusinessException(401, "用户未登录或登录态无效");
            }
            return null;
        }

        if (UserPrincipal.class.isAssignableFrom(parameter.getParameterType())) {
            return UserPrincipal.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .password(user.getPassword())
                    .role(user.getRole())
                    .enabled("ACTIVE".equalsIgnoreCase(user.getStatus()))
                    .build();
        }

        return user;
    }
}
