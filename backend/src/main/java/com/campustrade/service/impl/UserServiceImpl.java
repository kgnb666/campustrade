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
        UserCredit userCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, user.getId())
        );
        if (userCredit == null) {
            // 防御性初始化
            userCredit = UserCredit.builder()
                    .userId(user.getId())
                    .creditScore(100)
                    .tradeCount(0)
                    .goodReviewCount(0)
                    .badReviewCount(0)
                    .createdTime(LocalDateTime.now())
                    .build();
            userCreditMapper.insert(userCredit);
        }

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
                        .creditScore(userCredit.getCreditScore())
                        .tradeCount(userCredit.getTradeCount())
                        .goodReviewCount(userCredit.getGoodReviewCount())
                        .badReviewCount(userCredit.getBadReviewCount())
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
