package com.campustrade.service;

import com.campustrade.dto.UpdateProfileDTO;
import com.campustrade.vo.UserProfileVO;

/**
 * 用户服务接口
 *
 * <h2>返回领域类型，不返回 Web 信封</h2>
 * <p>与其它领域服务保持一致：只返回领域类型（{@link UserProfileVO}），失败以业务异常表达，
 * {@code Result} 信封由 Controller 统一包装。跨服务调用因此不再需要 {@code .getData()} 解包，
 * 也不会出现"信封为错误时静默拿到 null"这类隐患。</p>
 */
public interface UserService {

    /**
     * 获取当前用户详情 (个人信息、认证状态、信用档案)
     *
     * @throws com.campustrade.exception.BusinessException 用户不存在时抛出 404 业务异常
     */
    UserProfileVO getProfile(String username);

    /**
     * 修改当前用户个人资料
     */
    UserProfileVO updateProfile(String username, UpdateProfileDTO dto);

    /**
     * 根据用户名获取用户实体
     */
    com.campustrade.entity.User getByUsername(String username);
}
