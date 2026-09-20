package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.config.VerifyProperties;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.service.mail.VerifyCodeMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
 *   <li><b>发送次数按"发起人 × 邮箱"限流</b>：同一发起人对同一校园邮箱 24 小时 3 次、
 *       同一发起人 10 分钟 3 次。维度必须是"发起人 × 邮箱"而不是"仅邮箱"，否则任意账号都能把
 *       他人邮箱的当日额度打满（被攻击者当天无法认证，还会持续收到垃圾验证码邮件）；</li>
 *   <li><b>核验失败计数同为"发起人 × 邮箱"</b>：否则攻击者可以对他人邮箱连输 5 次错误验证码，
 *       把对方正在使用的验证码作废；</li>
 *   <li><b>邮箱唯一性</b>：{@code student_verify(school_id, school_email)} 上有一条
 *       {@code WHERE verify_status='SUCCESS'} 的部分唯一索引（V12），核销前再做一次应用层校验，
 *       保证"一个校园邮箱只绑定一个账号"；</li>
 *   <li><b>认证状态边界不变</b>：{@code verifyStatus=SUCCESS} 仍然只由"验证码核销成功"这一条路径写入。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentVerifyServiceImpl implements StudentVerifyService {

    // 状态字面量统一来自枚举：本类只保留短别名，避免把常量名散进每一处比较与查询条件
    private static final String STATUS_PENDING = StudentVerifyStatus.PENDING.getCode();
    private static final String STATUS_SUCCESS = StudentVerifyStatus.SUCCESS.getCode();

    private final UserMapper userMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final StudentVerifyMapper studentVerifyMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final VerifyProperties verifyProperties;
    private final VerifyCodeMailSender verifyCodeMailSender;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitVerify(String username, StudentVerifyDTO dto) {
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

        // 4. 发送频率限制：按用户（10min/3 次）与"发起人 × 邮箱"（24h/3 次）双向限流，超限直接拒绝
        enforceSendLimits(user.getId(), email);

        // 5. 生成 6 位随机数字验证码并写入 Redis (TTL: 5 分钟)
        String verifyCode = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
        String redisKey = RedisKeyConstants.studentVerifyKey(email);
        int codeTtlMinutes = verifyProperties.getCodeTtlMinutes();
        stringRedisTemplate.opsForValue().set(redisKey, verifyCode, codeTtlMinutes, TimeUnit.MINUTES);

        // 新验证码生效即重置旧的失败计数：上一次"输错 4 次"不应消耗新验证码的试错机会
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyFailKey(user.getId(), email));

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
        if (existingVerify != null && !STATUS_SUCCESS.equalsIgnoreCase(existingVerify.getVerifyStatus())) {
            // 定点更新 + 前置条件：只改写四个业务字段，并把"不是 SUCCESS"作为 WHERE 条件。
            // 原实现用 updateById(整行回写)：并发下会把同一行的其它字段（例如刚刚核销写入的
            // verify_status / verify_time）用旧快照覆盖回去（本人自伤）。
            int updated = studentVerifyMapper.update(null, new LambdaUpdateWrapper<StudentVerify>()
                    .set(StudentVerify::getSchoolId, school.getId())
                    .set(StudentVerify::getStudentNumber, dto.getStudentNumber().trim())
                    .set(StudentVerify::getSchoolEmail, email)
                    .set(StudentVerify::getVerifyStatus, STATUS_PENDING)
                    .eq(StudentVerify::getId, existingVerify.getId())
                    .ne(StudentVerify::getVerifyStatus, STATUS_SUCCESS));
            if (updated != 1) {
                log.info("认证申请行状态已变更，跳过 PENDING 改写: userId={}, verifyId={}, updated={}",
                        user.getId(), existingVerify.getId(), updated);
                throw new BusinessException(409, "认证状态已变更，请刷新后重新提交认证");
            }
        } else if (existingVerify == null) {
            StudentVerify newVerify = StudentVerify.builder()
                    .userId(user.getId())
                    .schoolId(school.getId())
                    .studentNumber(dto.getStudentNumber().trim())
                    .schoolEmail(email)
                    .verifyStatus(STATUS_PENDING)
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifyCode(String username, StudentVerifyCodeDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }

        String email = dto.getSchoolEmail().trim().toLowerCase();
        String redisKey = RedisKeyConstants.studentVerifyKey(email);
        String failKey = RedisKeyConstants.studentVerifyFailKey(user.getId(), email);
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);

        if (cachedCode == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "验证码已过期或未获取，请重新获取");
        }

        if (!cachedCode.equals(dto.getVerifyCode().trim())) {
            // 失败计数：累计达到上限即作废验证码（内部直接抛出 429 业务错误）
            recordVerifyFailure(user.getId(), email, redisKey, failKey);
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

        // 应用层唯一性校验：同一校园邮箱只能被一个账号核销成功。
        // 数据库侧由 V12 的部分唯一索引 uk_student_verify_email_success 兜底（并发下最终一致）。
        Long verifiedByOthers = studentVerifyMapper.selectCount(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getSchoolId, verifyRecord.getSchoolId())
                        .eq(StudentVerify::getSchoolEmail, email)
                        .eq(StudentVerify::getVerifyStatus, STATUS_SUCCESS)
                        .ne(StudentVerify::getUserId, user.getId())
        );
        if (verifiedByOthers != null && verifiedByOthers > 0) {
            log.warn("校园邮箱已被其他账号认证，拒绝重复核销: email={}, userId={}, schoolId={}",
                    VerifyCodeMailSender.maskEmail(email), user.getId(), verifyRecord.getSchoolId());
            throw new BusinessException(409, "该校园邮箱已被其他账号完成认证，一个校园邮箱只能绑定一个账号；如有疑问请联系平台管理员");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated;
        try {
            // 定点更新 + 前置条件：只把"仍是 PENDING 的那一行"改为 SUCCESS，并校验受影响行数。
            // 整行回写在并发下会互相覆盖（本人自伤）；条件更新让"谁先核销谁生效"具备确定性。
            updated = studentVerifyMapper.update(null, new LambdaUpdateWrapper<StudentVerify>()
                    .set(StudentVerify::getVerifyStatus, STATUS_SUCCESS)
                    .set(StudentVerify::getVerifyTime, now)
                    .eq(StudentVerify::getId, verifyRecord.getId())
                    .eq(StudentVerify::getVerifyStatus, STATUS_PENDING));
        } catch (DuplicateKeyException e) {
            // 并发下另一账号抢先核销了同一邮箱：数据库部分唯一索引兜底拦截
            log.warn("并发核销命中校园邮箱唯一索引: email={}, userId={}",
                    VerifyCodeMailSender.maskEmail(email), user.getId());
            throw new BusinessException(409, "该校园邮箱已被其他账号完成认证，一个校园邮箱只能绑定一个账号；如有疑问请联系平台管理员");
        }

        if (updated != 1) {
            log.warn("认证核销未生效（行状态已变更或不存在）: userId={}, verifyId={}, updated={}",
                    user.getId(), verifyRecord.getId(), updated);
            if (STATUS_SUCCESS.equalsIgnoreCase(verifyRecord.getVerifyStatus())) {
                throw new BusinessException(409, "该校园邮箱已完成认证，无需重复核销");
            }
            throw new BusinessException(409, "认证申请状态已变更，请重新提交认证");
        }

        // 核销后清理 Redis 中的验证码与失败计数
        stringRedisTemplate.delete(redisKey);
        stringRedisTemplate.delete(failKey);
        log.info("用户校园认证成功: userId={}, username={}, schoolEmail={}",
                user.getId(), username, VerifyCodeMailSender.maskEmail(email));
    }

    // =========================================================================
    // 内部限流与失败锁定
    // =========================================================================

    /**
     * 发送频率限制：按"发起人 × 邮箱"与按用户两个维度计数，任一超限即拒绝（429 语义）。
     *
     * <p>先用户后邮箱：同一账号用不同邮箱轮番轰炸时优先给出"请求过于频繁"，
     * 再由"发起人 × 邮箱"的 24 小时配额收敛跨窗口的重复请求。</p>
     */
    private void enforceSendLimits(Long userId, String email) {
        enforceLimit(
                "user", "userId=" + userId,
                RedisKeyConstants.studentVerifySendUserKey(userId),
                verifyProperties.getUserSendWindowMinutes(), TimeUnit.MINUTES,
                verifyProperties.getSendLimitPerUser(),
                "验证码请求过于频繁，请 " + verifyProperties.getUserSendWindowMinutes() + " 分钟后再试"
        );
        enforceLimit(
                "user-email", "userId=" + userId + ", email=" + VerifyCodeMailSender.maskEmail(email),
                RedisKeyConstants.studentVerifySendUserEmailKey(userId, email),
                verifyProperties.getUserEmailSendWindowHours(), TimeUnit.HOURS,
                verifyProperties.getSendLimitPerUserEmail(),
                "同一校园邮箱 24 小时内最多可获取 " + verifyProperties.getSendLimitPerUserEmail()
                        + " 次验证码，请稍后再试（换账号请求他人邮箱同样受限）"
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
     * <p>计数维度为（发起人 userId × 目标邮箱）：只按邮箱计数时，攻击者可以对他人邮箱
     * 连输 5 次错误验证码，把对方正在使用的验证码恶意作废。</p>
     *
     * <p>作废方式为直接删除 Redis 中的验证码：此后即使提交正确的验证码也会得到
     * "验证码已过期或未获取"，必须重新发送（重新发送会重置本计数）。</p>
     */
    private void recordVerifyFailure(Long userId, String email, String redisKey, String failKey) {
        Long failures = stringRedisTemplate.opsForValue().increment(failKey);
        stringRedisTemplate.expire(failKey, verifyProperties.getCodeTtlMinutes(), TimeUnit.MINUTES);

        int maxFailures = verifyProperties.getMaxFailures();
        if (failures != null && failures >= maxFailures) {
            stringRedisTemplate.delete(redisKey);
            log.warn("校园认证验证码核验失败次数达到上限，验证码已作废: userId={}, email={}, failures={}, maxFailures={}",
                    userId, VerifyCodeMailSender.maskEmail(email), failures, maxFailures);
            throw new BusinessException(429, "验证码错误次数过多，本次验证码已失效，请重新获取验证码");
        }
    }
}
