package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.ResultCode;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.config.VerifyProperties;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.dto.ManualVerifyRequest;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.enums.VerifyMethod;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.service.mail.VerifyCodeMailSender;
import com.campustrade.vo.StudentVerifyStatusVO;
import com.campustrade.vo.VerifySubmitVO;
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
    public VerifySubmitVO submitVerify(String username, StudentVerifyDTO dto) {
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

        // 4. 发送频率限制：按用户（10min/3 次）与"发起人 × 邮箱"（24h/3 次）双向限流，超限直接拒绝。
        //    演示通道（白名单邮箱）跳过限流：限流的两个理由在演示通道上都不成立——那台环境根本不发信
        //    （没有"打满他人邮箱额度 / 投递垃圾邮件"的问题），而演示本身就要反复点几次才能演完；
        //    若照旧限流，演示到第 4 次就会卡在"请求过于频繁，请 10 分钟后再试"，24 小时内更是只能用 3 次。
        boolean demoEmail = verifyProperties.isDemoEmail(email);
        if (demoEmail) {
            log.warn("校园认证演示模式：跳过发送限流（仅白名单邮箱）: email={}, userId={}",
                    VerifyCodeMailSender.maskEmail(email), user.getId());
        } else {
            enforceSendLimits(user.getId(), email);
        }

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
        if (existingVerify != null
                && (!STATUS_SUCCESS.equalsIgnoreCase(existingVerify.getVerifyStatus()) || demoEmail)) {
            // 演示通道下的第二个例外：白名单邮箱**已经认证成功**时，也把这一行重置回 PENDING。
            // 否则同一个演示邮箱只能演示一次——第二轮会在核销时报"该校园邮箱已完成认证，无需重复核销"，
            // 而演示要看的恰恰是"未认证 → 认证成功"这次状态跃迁。重置只作用于调用者自己名下的记录，
            // 且只对配置点名的演示邮箱生效；唯一索引仅约束 SUCCESS 行，重置为 PENDING 不会与之冲突。
            if (demoEmail && STATUS_SUCCESS.equalsIgnoreCase(existingVerify.getVerifyStatus())) {
                log.warn("校园认证演示模式：将已认证的演示邮箱重置为待认证，以便重复演示（仅白名单邮箱）: email={}, userId={}",
                        VerifyCodeMailSender.maskEmail(email), user.getId());
            }

            // 定点更新 + 前置条件：只改写四个业务字段，并把"不是 SUCCESS"作为 WHERE 条件。
            // 原实现用 updateById(整行回写)：并发下会把同一行的其它字段（例如刚刚核销写入的
            // verify_status / verify_time）用旧快照覆盖回去（本人自伤）。
            LambdaUpdateWrapper<StudentVerify> resetToPending = new LambdaUpdateWrapper<StudentVerify>()
                    .set(StudentVerify::getSchoolId, school.getId())
                    .set(StudentVerify::getStudentNumber, dto.getStudentNumber().trim())
                    .set(StudentVerify::getSchoolEmail, email)
                    .set(StudentVerify::getVerifyStatus, STATUS_PENDING)
                    .eq(StudentVerify::getId, existingVerify.getId());
            if (!demoEmail) {
                // 非演示通道必须保留"不是 SUCCESS 才改写"的前置条件：SUCCESS 行不允许被申请动作回退
                resetToPending.ne(StudentVerify::getVerifyStatus, STATUS_SUCCESS);
            }
            int updated = studentVerifyMapper.update(null, resetToPending);
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

        // 7. 下发验证码：演示通道（仅白名单邮箱）优先，其余走真实邮件 / 本地开发日志通道
        VerifyCodeMailSender.Channel channel;
        try {
            channel = verifyCodeMailSender.sendVerifyCode(email, school.getSchoolName(), verifyCode);
        } catch (BusinessException e) {
            // 下发失败时清理刚生成的验证码：避免留下"学生永远收不到、却真实可用"的验证码
            stringRedisTemplate.delete(redisKey);
            throw e;
        }

        if (channel == VerifyCodeMailSender.Channel.DEMO) {
            // 演示通道：验证码随响应返回给调用方（见 VerifySubmitVO 的说明）。
            // 这里刻意不打验证码明文——它已经交给调用方，再落进日志只会扩大凭据的留存面。
            log.warn("校园认证走演示通道：验证码随响应返回，未发送真实邮件（仅限演示环境）: email={}, userId={}",
                    VerifyCodeMailSender.maskEmail(email), user.getId());
            return VerifySubmitVO.builder().demoMode(true).demoCode(verifyCode).build();
        }

        // 安全要求：验证码属于一次性凭据，绝不能写入业务日志（日志会被长期留存并被多人查看）；
        // 这里只记录掩码后的校园邮箱、TTL 与用户 ID，足够定位问题但不含任何凭据信息。
        log.info("校园认证验证码已生成并下发: email={}, ttlMinutes={}, userId={}",
                VerifyCodeMailSender.maskEmail(email), codeTtlMinutes, user.getId());
        return null;
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
    // 无邮箱通道（学生证 + 管理员人工审核）
    // =========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitManualVerify(String username, ManualVerifyRequest dto) {
        User user = requireUser(username);
        CampusSchool school = requireSchool(dto.getSchoolId());

        String studentNumber = dto.getStudentNumber().trim();
        // 姓名与照片是**可选**的加分材料：空值统一归一化为 null，
        // 既避免把空字符串写进库，也避免审核页把它展示成"看起来填过但内容缺失"
        String realName = trimToNull(dto.getRealName());
        String evidenceUrl = trimToNull(dto.getEvidenceUrl());

        // 1. 提交限流：这条通道没有发信成本，但**审核是人工的**，不限流就会有人把审核队列刷满
        enforceLimit(
                "manual-submit", "userId=" + user.getId(),
                RedisKeyConstants.studentVerifyManualUserKey(user.getId()),
                1, TimeUnit.HOURS, verifyProperties.getManualSubmitLimitPerHour(),
                "认证材料提交过于频繁，请稍后再试"
        );

        // 2. 已认证（无论哪条通道）不再受理：重复提交只会给审核队列添乱
        StudentVerify latest = latestVerify(user.getId());
        if (latest != null && StudentVerifyStatus.SUCCESS.matches(latest.getVerifyStatus())) {
            throw new BusinessException(409, "你已完成校园身份认证，无需重复提交");
        }

        // 3. 学号占用校验：邮箱通道靠 V12 的邮箱唯一索引防"一人多号"，
        //    人工通道没有邮箱，改由"学校 + 学号"承担同一职责（V13 的部分唯一索引兜底并发）
        Long occupiedByOthers = studentVerifyMapper.selectCount(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getSchoolId, school.getId())
                        .eq(StudentVerify::getStudentNumber, studentNumber)
                        .eq(StudentVerify::getVerifyStatus, STATUS_SUCCESS)
                        .ne(StudentVerify::getUserId, user.getId())
        );
        if (occupiedByOthers != null && occupiedByOthers > 0) {
            log.warn("人工认证通道：学号已被其他账号认证: schoolId={}, studentNumber={}, userId={}",
                    school.getId(), studentNumber, user.getId());
            throw new BusinessException(409,
                    "该学号已被其他账号完成认证，一个学号只能绑定一个账号；如有疑问请联系平台管理员");
        }

        LocalDateTime now = LocalDateTime.now();
        if (latest == null) {
            studentVerifyMapper.insert(StudentVerify.builder()
                    .userId(user.getId())
                    .schoolId(school.getId())
                    .studentNumber(studentNumber)
                    .realName(realName)
                    .evidenceUrl(evidenceUrl)
                    .verifyMethod(VerifyMethod.MANUAL.getCode())
                    .verifyStatus(STATUS_PENDING)
                    .createdTime(now)
                    .build());
        } else {
            // 重新提交 = 覆盖上一轮材料并回到待审核：
            //   - 清空上一轮的审核结论，否则"待审核"还挂着旧的驳回理由，学生看到的状态自相矛盾；
            //   - 清空 school_email：切到人工通道后邮箱不再是证据，留着会让两条通道的字段互相污染。
            int updated = studentVerifyMapper.update(null, new LambdaUpdateWrapper<StudentVerify>()
                    .set(StudentVerify::getSchoolId, school.getId())
                    .set(StudentVerify::getStudentNumber, studentNumber)
                    .set(StudentVerify::getRealName, realName)
                    .set(StudentVerify::getEvidenceUrl, evidenceUrl)
                    .set(StudentVerify::getVerifyMethod, VerifyMethod.MANUAL.getCode())
                    .set(StudentVerify::getVerifyStatus, STATUS_PENDING)
                    .set(StudentVerify::getSchoolEmail, null)
                    .set(StudentVerify::getReviewNote, null)
                    .set(StudentVerify::getReviewerId, null)
                    .set(StudentVerify::getReviewTime, null)
                    .eq(StudentVerify::getId, latest.getId())
                    .ne(StudentVerify::getVerifyStatus, STATUS_SUCCESS));
            if (updated != 1) {
                throw new BusinessException(409, "认证状态已变更，请刷新后重新提交");
            }
        }

        // 日志只记录账号与学校，不记录姓名与材料地址（后者指向学生证照片，属个人信息）
        log.info("校园认证人工通道材料已提交: userId={}, username={}, schoolId={}",
                user.getId(), username, school.getId());
    }

    @Override
    public StudentVerifyStatusVO getMyVerifyStatus(String username) {
        User user = requireUser(username);
        StudentVerify latest = latestVerify(user.getId());
        if (latest == null) {
            return StudentVerifyStatusVO.builder().verified(false).build();
        }
        CampusSchool school = latest.getSchoolId() == null
                ? null : campusSchoolMapper.selectById(latest.getSchoolId());
        StudentVerifyStatus status = StudentVerifyStatus.fromCode(latest.getVerifyStatus());
        return StudentVerifyStatusVO.builder()
                .verifyStatus(latest.getVerifyStatus())
                .verifyMethod(latest.getVerifyMethod())
                .verifyStatusDesc(status != null ? status.getDescription() : latest.getVerifyStatus())
                .schoolId(latest.getSchoolId())
                .schoolName(school != null ? school.getSchoolName() : null)
                .studentNumber(latest.getStudentNumber())
                .realName(latest.getRealName())
                .evidenceUrl(latest.getEvidenceUrl())
                .reviewNote(latest.getReviewNote())
                .submittedTime(latest.getCreatedTime())
                .reviewTime(latest.getReviewTime())
                .verifyTime(latest.getVerifyTime())
                .verified(StudentVerifyStatus.SUCCESS.matches(latest.getVerifyStatus()))
                .build();
    }

    /** 取当前用户最新一条认证记录：两条通道共用同一行，"最新一条"即当前状态。 */
    private StudentVerify latestVerify(Long userId) {
        return studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, userId)
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1")
        );
    }

    private User requireUser(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }

    private CampusSchool requireSchool(Long schoolId) {
        CampusSchool school = schoolId == null ? null : campusSchoolMapper.selectById(schoolId);
        if (school == null || !"ACTIVE".equalsIgnoreCase(school.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "选择的高校不存在或已暂停服务");
        }
        return school;
    }

    /** 空白字符串归一化为 null（可选字段的统一处理） */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
