package com.campustrade.service;

import com.campustrade.common.Result;
import com.campustrade.dto.UpdateProfileDTO;
import com.campustrade.vo.UserProfileVO;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 获取当前用户详情 (个人信息、认证状态、信用档案)
     */
    Result<UserProfileVO> getProfile(String username);

    /**
     * 修改当前用户个人资料
     */
    Result<UserProfileVO> updateProfile(String username, UpdateProfileDTO dto);

    /**
     * 根据用户名获取用户实体
     */
    com.campustrade.entity.User getByUsername(String username);
}
