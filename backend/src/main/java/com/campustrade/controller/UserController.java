package com.campustrade.controller;

import com.campustrade.common.Result;
import com.campustrade.dto.UpdateProfileDTO;
import com.campustrade.security.SecurityUtils;
import com.campustrade.service.UserService;
import com.campustrade.vo.UserProfileVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户个人中心控制器
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前用户个人资料、认证状态及信用档案
     */
    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile() {
        String username = SecurityUtils.getCurrentUsername();
        return userService.getProfile(username);
    }

    /**
     * 修改当前用户个人资料
     */
    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        String username = SecurityUtils.getCurrentUsername();
        return userService.updateProfile(username, dto);
    }
}
