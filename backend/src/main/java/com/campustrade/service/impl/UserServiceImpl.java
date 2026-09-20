package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.dto.UpdateProfileDTO;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.UserService;
import com.campustrade.vo.UserCreditVO;
import com.campustrade.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 用户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserCreditMapper userCreditMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final CampusSchoolMapper campusSchoolMapper;

    @Override
    public Result<UserProfileVO> getProfile(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        // 1. 查询信用档案
        //    GET 路径绝不产生写副作用：档案缺失时只返回默认视图（100 分 / 0 计数），不回写数据库。
        //    原因：读接口并发（同一新用户多端同时首刷）会在"查不到就 insert"上撞 user_credit_user_id_key
        //    唯一键，冲突后 PostgreSQL 事务已被标记 aborted，catch 里再查必然失败 → 500。
        //    档案的真正创建统一由 CreditService.getOrCreateCredit（INSERT ... ON CONFLICT DO NOTHING）负责。
        UserCredit userCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, user.getId())
        );
        if (userCredit == null) {
            log.debug("用户信用档案尚未建立，GET /profile 仅返回默认信用视图（不落库）: userId={}", user.getId());
        }
        int creditScore = (userCredit != null && userCredit.getCreditScore() != null)
                ? userCredit.getCreditScore() : 100;
        int tradeCount = (userCredit != null && userCredit.getTradeCount() != null)
                ? userCredit.getTradeCount() : 0;
        int goodReviewCount = (userCredit != null && userCredit.getGoodReviewCount() != null)
                ? userCredit.getGoodReviewCount() : 0;
        int badReviewCount = (userCredit != null && userCredit.getBadReviewCount() != null)
                ? userCredit.getBadReviewCount() : 0;

        // 2. 查询最新学籍认证状态
        StudentVerify studentVerify = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1")
        );

        String verifyStatus = "NONE";
        String schoolName = null;
        String studentNumber = null;

        if (studentVerify != null) {
            verifyStatus = studentVerify.getVerifyStatus();
            studentNumber = studentVerify.getStudentNumber();
            CampusSchool school = campusSchoolMapper.selectById(studentVerify.getSchoolId());
            if (school != null) {
                schoolName = school.getSchoolName();
            }
        }

        // 3. 构建聚合视图对象
        UserProfileVO profileVO = UserProfileVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .verifyStatus(verifyStatus)
                .schoolName(schoolName)
                .studentNumber(studentNumber)
                .credit(UserCreditVO.builder()
                        .creditScore(creditScore)
                        .tradeCount(tradeCount)
                        .goodReviewCount(goodReviewCount)
                        .badReviewCount(badReviewCount)
                        .build())
                .build();

        return Result.success(profileVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<UserProfileVO> updateProfile(String username, UpdateProfileDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        boolean updated = false;
        if (StringUtils.hasText(dto.getNickname())) {
            user.setNickname(dto.getNickname().trim());
            updated = true;
        }
        if (StringUtils.hasText(dto.getAvatar())) {
            user.setAvatar(dto.getAvatar().trim());
            updated = true;
        }
        if (StringUtils.hasText(dto.getPhone())) {
            user.setPhone(dto.getPhone().trim());
            updated = true;
        }

        if (updated) {
            user.setUpdatedTime(LocalDateTime.now());
            userMapper.updateById(user);
            log.info("用户资料已更新: username={}, userId={}", username, user.getId());
        }

        return getProfile(username);
    }

    @Override
    public User getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username.trim())
        );
    }
}
