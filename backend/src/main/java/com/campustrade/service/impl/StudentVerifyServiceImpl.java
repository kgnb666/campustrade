package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Result;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.config.VerifyProperties;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.service.mail.VerifyCodeMailSender;
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
 *
 * <h2>可信性设计（为什么验证码"看得见"才算真的可信）</h2>
 * <ul>
 *   <li><b>验证码只走真实邮件</b>：生成后由 {@link VerifyCodeMailSender} 下发到学生校园邮箱，
 *       响应体与业务日志都不再携带验证码（{@code data} 恒为 null）；</li>
 *   <li><b>本地开发通道是可配置的例外</b>：{@code verify.mail-enabled=false} 时验证码只写入服务端
 *       日志（{@code [DEV-ONLY]} 行），prod profile 下该组合会直接拒绝启动；</li>
 *   <li><b>验证码不落库</b>：MyBatis 的参数日志（{@code com.baomidou.mybatisplus: DEBUG}）会把 INSERT/UPDATE
 *       的实参原样打印进日志文件，因此验证码只存 Redis，绝不写入 {@code student_verify.verify_code}；</li>
 *   <li><b>可试错次数有限</b>：同一验证码核验失败累计 5 次即作废，必须重新获取，
 *       把 6 位验证码从"可无限枚举"收敛为"最多 5 次机会"；</li>
 *   <li><b>发送次数受限</b>：按邮箱（24 小时 5 次）与按用户（10 分钟 3 次）双向限流，
 *       既防"拿一个邮箱反复轰炸"，也防"换邮箱刷同一账号"；</li>
 *   <li><b>认证状态边界不变</b>：{@code verifyStatus=SUCCESS} 仍然只由"验证码核销成功"这一条路径写入。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentVerifyServiceImpl implements StudentVerifyService {

    private final UserMapper userMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final VerifyProperties verifyProperties;
    private final VerifyCodeMailSender verifyCodeMailSender;

    /** 验证码 Redis 键前缀，与 {@link RedisKeyConstants#STUDENT_VERIFY_PREFIX} 一致。 */
    public static final String VERIFY_CODE_PREFIX = RedisKeyConstants.STUDENT_VERIFY_PREFIX;

    /** 下发成功后的统一提示：不含任何验证码信息（响应 data 恒为 null）。 */
    public static final String VERIFY_CODE_SENT_MESSAGE = "验证码已发送至校园邮箱（5分钟内有效）";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> submitVerify(String username, StudentVerifyDTO dto) {
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

        // 3. 校验校园邮箱后缀是否与学校匹配（安全边界保持：邮箱必须属于所选学校）
        String email = dto.getSchoolEmail().trim().toLowerCase();
        String expectedSuffix = school.getEmailSuffix().trim().toLowerCase();
        if (!email.endsWith(expectedSuffix)) {
            throw new BusinessException(
                    ResultCode.BAD_REQUEST.getCode(),
                    String.format("邮箱后缀与所选学校不匹配，%s 的校园邮箱后缀须为: %s", school.getSchoolName(), expectedSuffix)
            );
        }

        // 4. 发送频率限制：按邮箱（24h/5 次）与按用户（10min/3 次）双向限流，超限直接拒绝
        enforceSendLimits(user.getId(), email);

        // 5. 生成 6 位随机数字验证码并写入 Redis (TTL: 5 分钟)
        String verifyCode = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
        String redisKey = RedisKeyConstants.studentVerifyKey(email);
        int codeTtlMinutes = verifyProperties.getCodeTtlMinutes();
        stringRedisTemplate.opsForValue().set(redisKey, verifyCode, codeTtlMinutes, TimeUnit.MINUTES);

        // 新验证码生效即重置旧的失败计数：上一次"输错 4 次"不应消耗新验证码的试错机会
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyFailKey(email));

        // 6. 保存或更新学生认证记录为 PENDING 状态
        //    注意：刻意不写入 verify_code —— 该字段是验证码明文，而 MyBatis 参数日志会把 SQL 实参打进日志文件，
        //    一旦落库验证码就会出现在日志里，等于把"可信认证"重新变成"看日志即可自证"。
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
            existingVerify.setVerifyStatus("PENDING");
            studentVerifyMapper.updateById(existingVerify);
        } else if (existingVerify == null) {
            StudentVerify newVerify = StudentVerify.builder()
                    .userId(user.getId())
                    .schoolId(school.getId())
                    .studentNumber(dto.getStudentNumber().trim())
                    .schoolEmail(email)
                    .verifyStatus("PENDING")
                    .createdTime(now)
                    .build();
            studentVerifyMapper.insert(newVerify);
        }

        // 7. 下发验证码：真实邮件（mail-enabled=true）或本地开发日志通道
        try {
            verifyCodeMailSender.sendVerifyCode(email, school.getSchoolName(), verifyCode);
        } catch (BusinessException e) {
            // 下发失败时清理刚生成的验证码：避免留下"学生永远收不到、却真实可用"的验证码
            stringRedisTemplate.delete(redisKey);
            throw e;
        }

        // 安全要求：验证码属于一次性凭据，绝不能写入业务日志（日志会被长期留存并被多人查看）；
        // 这里只记录掩码后的校园邮箱、TTL 与用户 ID，足够定位问题但不含任何凭据信息。
        log.info("校园认证验证码已生成并下发: email={}, ttlMinutes={}, userId={}",
                VerifyCodeMailSender.maskEmail(email), codeTtlMinutes, user.getId());

        // 响应不再携带验证码：data 恒为 null，前端必须走"查收邮件/查看本地日志"的正规路径
        return Result.success(VERIFY_CODE_SENT_MESSAGE, null);
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
        String redisKey = RedisKeyConstants.studentVerifyKey(email);
        String failKey = RedisKeyConstants.studentVerifyFailKey(email);
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);

        if (cachedCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期或未获取，请重新获取");
        }

        if (!cachedCode.equals(dto.getVerifyCode().trim())) {
            // 失败计数：累计达到上限即作废验证码（内部直接抛出 429 业务错误）
            recordVerifyFailure(email, redisKey, failKey);
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码错误，请重新输入");
        }

        // 验证码核销成功，更新学生认证状态为 SUCCESS
        // （安全边界：SUCCESS 只能由"验证码核销成功"这一条路径写入，不得放松）
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

        // 核销后清理 Redis 中的验证码与失败计数
        stringRedisTemplate.delete(redisKey);
        stringRedisTemplate.delete(failKey);
        log.info("用户校园认证成功: userId={}, username={}, schoolEmail={}",
                user.getId(), username, VerifyCodeMailSender.maskEmail(email));

        return Result.success("校园身份认证成功！已为您点亮高校专属认证标识", null);
    }

    // =========================================================================
    // 内部限流与失败锁定
    // =========================================================================

    /**
     * 发送频率限制：按校园邮箱与按用户两个维度计数，任一超限即拒绝（429 语义）。
     *
     * <p>先邮箱后用户：同一邮箱被反复轰炸时优先给出"该邮箱已达上限"，避免多账号轮番请求同一邮箱。</p>
     */
    private void enforceSendLimits(Long userId, String email) {
        enforceLimit(
                "email", VerifyCodeMailSender.maskEmail(email),
                RedisKeyConstants.studentVerifySendEmailKey(email),
                verifyProperties.getEmailSendWindowHours(), TimeUnit.HOURS,
                verifyProperties.getSendLimitPerEmail(),
                "该校园邮箱 24 小时内验证码发送次数已达上限，请稍后再试"
        );
        enforceLimit(
                "user", "userId=" + userId,
                RedisKeyConstants.studentVerifySendUserKey(userId),
                verifyProperties.getUserSendWindowMinutes(), TimeUnit.MINUTES,
                verifyProperties.getSendLimitPerUser(),
                "验证码请求过于频繁，请 " + verifyProperties.getUserSendWindowMinutes() + " 分钟后再试"
        );
    }

    /**
     * 计数 + 阈值判定（沿用项目既有的"先 INCR 再比较"限流风格，见 AuthServiceImpl#enforceRegisterIpLimit）。
     *
     * <p>窗口为滑动窗口：每次请求都会刷新 TTL，因此持续请求只会把自己挡得更久。
     * 超过上限的请求同样会计数——限流窗口内的请求次数不会因为被拒绝而"免费"。</p>
     *
     * <p>日志只打印限流维度、掩码身份与计数，不打印 Redis 键本身（键里含完整校园邮箱）。</p>
     */
    private void enforceLimit(String dimension, String identity, String key,
                              int window, TimeUnit unit, int limit, String message) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, window, unit);
        if (count != null && count > limit) {
            log.warn("校园认证验证码发送触发限流: dimension={}, identity={}, count={}, limit={}, window={}{}",
                    dimension, identity, count, limit, window, unit);
            throw new BusinessException(429, message);
        }
    }

    /**
     * 记录一次验证码核验失败；累计达到上限即作废当前验证码并抛出 429 业务错误。
     *
     * <p>作废方式为直接删除 Redis 中的验证码：此后即使提交正确的验证码也会得到
     * "验证码已过期或未获取"，必须重新发送（重新发送会重置本计数）。</p>
     */
    private void recordVerifyFailure(String email, String redisKey, String failKey) {
        Long failures = stringRedisTemplate.opsForValue().increment(failKey);
        stringRedisTemplate.expire(failKey, verifyProperties.getCodeTtlMinutes(), TimeUnit.MINUTES);

        int maxFailures = verifyProperties.getMaxFailures();
        if (failures != null && failures >= maxFailures) {
            stringRedisTemplate.delete(redisKey);
            log.warn("校园认证验证码核验失败次数达到上限，验证码已作废: email={}, failures={}, maxFailures={}",
                    VerifyCodeMailSender.maskEmail(email), failures, maxFailures);
            throw new BusinessException(429, "验证码错误次数过多，本次验证码已失效，请重新获取验证码");
        }
    }
}
