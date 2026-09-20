package com.campustrade.security;

import com.campustrade.common.ResultCode;
import com.campustrade.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 安全上下文辅助工具类
 */
public class SecurityUtils {

    /**
     * 获取当前登录用户名
     */
    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        } else if (principal instanceof String && !"anonymousUser".equals(principal)) {
            return (String) principal;
        }

        throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
    }

    /**
     * 安全获取当前登录用户名，未登录时返回 null 而不抛出异常
     */
    public static String getCurrentUsernameOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        } else if (principal instanceof String && !"anonymousUser".equals(principal)) {
            return (String) principal;
        }

        return null;
    }

    /**
     * 获取当前登录用户 ID (直接从 UserPrincipal 提取，零 DB-I/O)
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal && userPrincipal.getId() != null) {
            return userPrincipal.getId();
        }

        throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "未找到有效的当前用户凭据");
    }

    /**
     * 安全获取当前登录用户 ID，未登录或未认证时返回 null
     */
    public static Long getCurrentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }

        return null;
    }

    /**
     * 获取当前安全上下文中的 UserPrincipal 对象
     */
    public static UserPrincipal getCurrentUserPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }

        return null;
    }
}
