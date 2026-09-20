package com.campustrade.service.impl;

import com.campustrade.common.constant.CreditRule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    /**
     * 个人中心 {@code verifyStatus} 的兜底取值：该用户一条认证记录都没有。
     *
     * <p>刻意不放进 {@code StudentVerifyStatus} 枚举——它不是
     * {@code student_verify.verify_status} 的落库取值（V10 的 CHECK 只允许 PENDING / SUCCESS），
     * 而是"没有任何认证行"这一情况的展示标记。命名成常量是为了不再出现在业务代码里裸写的情况。</p>
     */
    private static final String VERIFY_STATUS_NONE = "NONE";

    private final UserMapper userMapper;
    private final UserCreditMapper userCreditMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final CampusSchoolMapper campusSchoolMapper;

    @Override
    public UserProfileVO getProfile(String username) {
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
                ? userCredit.getCreditScore() : CreditRule.SCORE_DEFAULT;
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

        // 个人中心展示用的兜底取值：用户一条认证记录都没有时，接口回 "NONE" 而不是 null。
        // 它不是 student_verify.verify_status 的落库取值，因此不放进 StudentVerifyStatus 枚举。
        String verifyStatus = VERIFY_STATUS_NONE;
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

        return profileVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileVO updateProfile(String username, UpdateProfileDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        // 定点更新：只写资料字段。
        // 不能"读整行 → 改字段 → updateById"：MyBatis-Plus 默认会把实体上所有非空字段拼进 SET，
        // 于是并发场景下会把管理员刚写入的 status=FROZEN 覆盖回 ACTIVE（冻结被静默撤销、审计与实际背离）。
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getUpdatedTime, LocalDateTime.now());
        boolean updated = false;
        if (StringUtils.hasText(dto.getNickname())) {
            update.set(User::getNickname, dto.getNickname().trim());
            updated = true;
        }
        if (StringUtils.hasText(dto.getAvatar())) {
            update.set(User::getAvatar, dto.getAvatar().trim());
            updated = true;
        }
        if (StringUtils.hasText(dto.getPhone())) {
            update.set(User::getPhone, dto.getPhone().trim());
            updated = true;
        }

        if (updated) {
            userMapper.update(null, update);
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
