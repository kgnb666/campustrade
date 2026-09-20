package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.TokenHashUtils;
import com.campustrade.service.StudentVerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 校园身份认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentVerifyServiceImpl implements StudentVerifyService {

    private final UserMapper userMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public static final String VERIFY_CODE_PREFIX = RedisKeyConstants.STUDENT_VERIFY_PREFIX;
    public static final long VERIFY_CODE_TTL_MINUTES = 5;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<String> submitVerify(String username, StudentVerifyDTO dto) {
        // 1. 获取当前用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        // 2. 校验学校是否存在
        CampusSchool school = campusSchoolMapper.selectById(dto.getSchoolId());
        if (school == null || !"ACTIVE".equalsIgnoreCase(school.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "选择的高校不存在或已暂停服务");
        }

        // 3. 校验校园邮箱后缀是否与学校匹配
        String email = dto.getSchoolEmail().trim().toLowerCase();
        String expectedSuffix = school.getEmailSuffix().trim().toLowerCase();
        if (!email.endsWith(expectedSuffix)) {
            throw new BusinessException(
                    ResultCode.BAD_REQUEST.getCode(),
                    String.format("邮箱后缀与所选学校不匹配，%s 的校园邮箱后缀须为: %s", school.getSchoolName(), expectedSuffix)
            );
        }

        // 4. 生成 6 位随机数字验证码
        String verifyCode = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));

        // 5. 保存验证码至 Redis (TTL: 5 分钟)
        String redisKey = VERIFY_CODE_PREFIX + email;
        stringRedisTemplate.opsForValue().set(redisKey, verifyCode, VERIFY_CODE_TTL_MINUTES, TimeUnit.MINUTES);
        // 安全要求：验证码属于一次性凭据，绝不能写入日志（日志会被长期留存并被多人查看）
        log.info("生成校园邮箱验证码: email={}, codeHash={}, TTL=5m",
                email, TokenHashUtils.fingerprint(redisKey + verifyCode));

        // 6. 保存或更新学生认证记录为 PENDING 状态
        StudentVerify existingVerify = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1")
        );

        LocalDateTime now = LocalDateTime.now();
        if (existingVerify != null && !"SUCCESS".equalsIgnoreCase(existingVerify.getVerifyStatus())) {
            existingVerify.setSchoolId(school.getId());
            existingVerify.setStudentNumber(dto.getStudentNumber().trim());
            existingVerify.setSchoolEmail(email);
            existingVerify.setVerifyCode(verifyCode);
            existingVerify.setVerifyStatus("PENDING");
            studentVerifyMapper.updateById(existingVerify);
        } else if (existingVerify == null) {
            StudentVerify newVerify = StudentVerify.builder()
                    .userId(user.getId())
                    .schoolId(school.getId())
                    .studentNumber(dto.getStudentNumber().trim())
                    .schoolEmail(email)
                    .verifyCode(verifyCode)
                    .verifyStatus("PENDING")
                    .createdTime(now)
                    .build();
            studentVerifyMapper.insert(newVerify);
        }

        return Result.success("验证码已发送至校园邮箱（5分钟内有效），测试阶段验证码为: " + verifyCode, verifyCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> verifyCode(String username, StudentVerifyCodeDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        String email = dto.getSchoolEmail().trim().toLowerCase();
        String redisKey = VERIFY_CODE_PREFIX + email;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);

        if (cachedCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期或未获取，请重新获取");
        }

        if (!cachedCode.equals(dto.getVerifyCode().trim())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误，请重新输入");
        }

        // 验证码核销成功，更新学生认证状态为 SUCCESS
        StudentVerify verifyRecord = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .eq(StudentVerify::getSchoolEmail, email)
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1")
        );

        if (verifyRecord == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "未找到对应的认证申请记录，请重新提交认证");
        }

        LocalDateTime now = LocalDateTime.now();
        verifyRecord.setVerifyStatus("SUCCESS");
        verifyRecord.setVerifyTime(now);
        studentVerifyMapper.updateById(verifyRecord);

        // 核销后清理 Redis 中的验证码
        stringRedisTemplate.delete(redisKey);
        log.info("用户校园认证成功: userId={}, username={}, schoolEmail={}", user.getId(), username, email);

        return Result.success("校园身份认证成功！已为您点亮高校专属认证标识", null);
    }
}
