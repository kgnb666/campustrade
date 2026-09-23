package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.entity.AdminAuditLog;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.enums.AdminOperationType;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.enums.VerifyMethod;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.AdminAuditLogMapper;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.AdminVerifyService;
import com.campustrade.vo.AdminVerifyReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「无邮箱通道」校园认证的人工审核实现。
 *
 * <h2>并发与唯一性</h2>
 * <ul>
 *   <li><b>谁先处置谁生效</b>：状态更新走"条件更新 + 校验受影响行数"
 *       （{@code WHERE id=? AND verify_status='PENDING'}），两个管理员同时点同一个申请时，
 *       后到者的 UPDATE 命中 0 行并整体回滚为 409，不会出现"通过一次、记录两次"的假象；</li>
 *   <li><b>学号唯一性双重校验</b>：应用层先查"该学号是否已被他人认证"，数据库侧再由 V13 的
 *       {@code uk_student_verify_school_number_success} 部分唯一索引兜底并发；</li>
 *   <li><b>审计必留痕</b>：每次处置写一条 {@code admin_audit_log}，记录处置人、动作、
 *       前后状态与审核意见，与举报治理共用一张表，便于在一处回溯管理员操作。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminVerifyServiceImpl implements AdminVerifyService {

    private static final String STATUS_PENDING = StudentVerifyStatus.PENDING.getCode();
    private static final String STATUS_SUCCESS = StudentVerifyStatus.SUCCESS.getCode();
    private static final String STATUS_REJECTED = StudentVerifyStatus.REJECTED.getCode();

    /** 审核意见长度上限：与 {@code student_verify.review_note VARCHAR(255)} 对齐 */
    private static final int MAX_NOTE_LENGTH = 255;

    private final StudentVerifyMapper studentVerifyMapper;
    private final UserMapper userMapper;
    private final CampusSchoolMapper campusSchoolMapper;
    private final AdminAuditLogMapper adminAuditLogMapper;

    @Override
    public IPage<AdminVerifyReviewVO> pageVerifies(String status, long page, long size) {
        StudentVerifyStatus target = StudentVerifyStatus.fromCode(status);
        if (target == null) {
            // 不传或传了非法值一律看"待审核"：审核队列的默认视角就是"还有什么没处理"
            target = StudentVerifyStatus.PENDING;
        }
        long current = Math.max(page, 1L);
        long pageSize = Math.min(Math.max(size, 1L), 100L);

        IPage<StudentVerify> rows = studentVerifyMapper.selectPage(
                new Page<>(current, pageSize),
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getVerifyMethod, VerifyMethod.MANUAL.getCode())
                        .eq(StudentVerify::getVerifyStatus, target.getCode())
                        // 先提交先审核：审核队列按时间正序，避免老申请被新申请"淹没"
                        .orderByAsc(StudentVerify::getCreatedTime)
        );

        Page<AdminVerifyReviewVO> result = new Page<>(current, pageSize, rows.getTotal());
        result.setRecords(toVOs(rows.getRecords()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminVerifyReviewVO reviewVerify(Long adminId, String adminUsername, Long verifyId,
                                            String action, String note, String ipAddress) {
        if (verifyId == null) {
            throw new BusinessException(400, "认证申请ID不能为空");
        }

        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        boolean approve = "APPROVE".equals(normalizedAction) || "PASS".equals(normalizedAction);
        boolean reject = "REJECT".equals(normalizedAction) || "DENY".equals(normalizedAction);
        if (!approve && !reject) {
            throw new BusinessException(400, "处理动作只支持 APPROVE（通过）或 REJECT（驳回）");
        }

        String trimmedNote = note == null ? null : note.trim();
        if (reject && !StringUtils.hasText(trimmedNote)) {
            // 驳回必须说明原因：不写原因等于让学生重新猜一遍，材料大概率还是会被驳回
            throw new BusinessException(400, "驳回申请必须填写原因（学生需要知道要修改什么）");
        }
        if (trimmedNote != null && trimmedNote.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(400, "审核意见不能超过 " + MAX_NOTE_LENGTH + " 个字符");
        }

        StudentVerify record = studentVerifyMapper.selectById(verifyId);
        if (record == null) {
            throw new BusinessException(404, "认证申请不存在");
        }
        if (!VerifyMethod.MANUAL.getCode().equalsIgnoreCase(record.getVerifyMethod())) {
            throw new BusinessException(400, "该申请不是「无邮箱通道」材料，无法在此处置");
        }
        if (!StudentVerifyStatus.PENDING.matches(record.getVerifyStatus())) {
            StudentVerifyStatus current = StudentVerifyStatus.fromCode(record.getVerifyStatus());
            throw new BusinessException(409, "该申请已处理过（当前状态："
                    + (current != null ? current.getDescription() : record.getVerifyStatus())
                    + "），请刷新列表");
        }

        if (approve) {
            Long verifiedByOthers = studentVerifyMapper.selectCount(
                    new LambdaQueryWrapper<StudentVerify>()
                            .eq(StudentVerify::getSchoolId, record.getSchoolId())
                            .eq(StudentVerify::getStudentNumber, record.getStudentNumber())
                            .eq(StudentVerify::getVerifyStatus, STATUS_SUCCESS)
                            .ne(StudentVerify::getUserId, record.getUserId())
            );
            if (verifiedByOthers != null && verifiedByOthers > 0) {
                throw new BusinessException(409, "该学号已被其他账号完成认证，一个学号只能绑定一个账号");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        String afterStatus = approve ? STATUS_SUCCESS : STATUS_REJECTED;

        int updated;
        try {
            LambdaUpdateWrapper<StudentVerify> update = new LambdaUpdateWrapper<StudentVerify>()
                    .set(StudentVerify::getVerifyStatus, afterStatus)
                    .set(StudentVerify::getReviewNote, trimmedNote)
                    .set(StudentVerify::getReviewerId, adminId)
                    .set(StudentVerify::getReviewTime, now)
                    .eq(StudentVerify::getId, verifyId)
                    .eq(StudentVerify::getVerifyStatus, STATUS_PENDING);
            if (approve) {
                update.set(StudentVerify::getVerifyTime, now);
            }
            updated = studentVerifyMapper.update(null, update);
        } catch (DuplicateKeyException e) {
            // 并发下另一个管理员刚通过了同一个学号：数据库部分唯一索引兜底拦截
            log.warn("通过认证时命中 (school_id, student_number) 唯一索引: verifyId={}, schoolId={}",
                    verifyId, record.getSchoolId());
            throw new BusinessException(409, "该学号已被其他账号完成认证，一个学号只能绑定一个账号");
        }

        if (updated != 1) {
            throw new BusinessException(409, "该申请已被其他管理员处理，请刷新列表");
        }

        adminAuditLogMapper.insert(AdminAuditLog.builder()
                .adminId(adminId)
                .adminUsername(adminUsername)
                .operationType(approve
                        ? AdminOperationType.PASS_STUDENT_VERIFY.getCode()
                        : AdminOperationType.REJECT_STUDENT_VERIFY.getCode())
                .targetType("STUDENT_VERIFY")
                .targetId(verifyId)
                .beforeStatus(STATUS_PENDING)
                .afterStatus(afterStatus)
                .reason(trimmedNote)
                .ipAddress(ipAddress)
                .createdTime(now)
                .build());

        log.info("管理员处置校园认证申请: adminId={}, adminUsername={}, verifyId={}, action={}, userId={}, afterStatus={}",
                adminId, adminUsername, verifyId, normalizedAction, record.getUserId(), afterStatus);

        StudentVerify refreshed = studentVerifyMapper.selectById(verifyId);
        return toVOs(Collections.singletonList(refreshed)).stream().findFirst().orElse(null);
    }

    /** 批量装配 VO：避免逐行查用户/学校造成 N+1 查询 */
    private List<AdminVerifyReviewVO> toVOs(List<StudentVerify> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> users = loadUsers(rows.stream().map(StudentVerify::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, CampusSchool> schools = loadSchools(rows.stream().map(StudentVerify::getSchoolId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, User> reviewers = loadUsers(rows.stream().map(StudentVerify::getReviewerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        return rows.stream().map(row -> {
            User applicant = users.get(row.getUserId());
            CampusSchool school = schools.get(row.getSchoolId());
            User reviewer = reviewers.get(row.getReviewerId());
            StudentVerifyStatus status = StudentVerifyStatus.fromCode(row.getVerifyStatus());
            return AdminVerifyReviewVO.builder()
                    .id(row.getId())
                    .userId(row.getUserId())
                    .username(applicant != null ? applicant.getUsername() : null)
                    .nickname(applicant != null ? applicant.getNickname() : null)
                    .schoolId(row.getSchoolId())
                    .schoolName(school != null ? school.getSchoolName() : null)
                    .studentNumber(row.getStudentNumber())
                    .realName(row.getRealName())
                    .evidenceUrl(row.getEvidenceUrl())
                    .verifyStatus(row.getVerifyStatus())
                    .verifyStatusDesc(status != null ? status.getDescription() : row.getVerifyStatus())
                    .verifyMethod(row.getVerifyMethod())
                    .reviewNote(row.getReviewNote())
                    .submittedTime(row.getCreatedTime())
                    .reviewTime(row.getReviewTime())
                    .reviewerName(reviewer != null ? reviewer.getUsername() : null)
                    .build();
        }).collect(Collectors.toList());
    }

    private Map<Long, User> loadUsers(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, CampusSchool> loadSchools(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return campusSchoolMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CampusSchool::getId, Function.identity(), (a, b) -> a));
    }
}
