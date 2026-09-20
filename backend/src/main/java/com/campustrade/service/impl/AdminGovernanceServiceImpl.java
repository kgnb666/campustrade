package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.report.ReportQueryRequest;
import com.campustrade.entity.*;
import com.campustrade.enums.*;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.*;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.CreditService;
import com.campustrade.vo.report.AdminAuditLogVO;
import com.campustrade.vo.report.AdminReportDetailVO;
import com.campustrade.vo.review.ReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 平台管理员治理与工单处理业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGovernanceServiceImpl implements AdminGovernanceService {

    private final ReportMapper reportMapper;
    private final AdminAuditLogMapper adminAuditLogMapper;
    private final GoodsMapper goodsMapper;
    private final ReviewMapper reviewMapper;
    private final UserMapper userMapper;
    private final CreditService creditService;

    @Override
    public IPage<AdminReportDetailVO> pageReports(ReportQueryRequest queryRequest) {
        int curPage = (queryRequest != null && queryRequest.getPage() != null && queryRequest.getPage() > 0)
                ? queryRequest.getPage() : 1;
        int pageSize = (queryRequest != null && queryRequest.getSize() != null && queryRequest.getSize() > 0 && queryRequest.getSize() <= 100)
                ? queryRequest.getSize() : 10;

        Page<Report> page = new Page<>(curPage, pageSize);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();

        if (queryRequest != null && StringUtils.hasText(queryRequest.getStatus())) {
            wrapper.eq(Report::getStatus, queryRequest.getStatus().trim().toUpperCase());
        }
        if (queryRequest != null && StringUtils.hasText(queryRequest.getTargetType())) {
            wrapper.eq(Report::getTargetType, queryRequest.getTargetType().trim().toUpperCase());
        }

        // 优先按待审核状态排序，然后按创建时间倒序
        wrapper.orderByDesc(Report::getCreatedTime);

        IPage<Report> reportPage = reportMapper.selectPage(page, wrapper);
        return reportPage.convert(this::convertToAdminDetailVO);
    }

    @Override
    public AdminReportDetailVO getReportDetail(Long reportId) {
        if (reportId == null) {
            throw new BusinessException(400, "工单ID不能为空");
        }
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException(404, "举报工单不存在");
        }
        return convertToAdminDetailVO(report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminReportDetailVO handleReport(
            Long adminId,
            String adminUsername,
            Long reportId,
            HandleReportRequest request,
            String ipAddress
    ) {
        if (adminId == null) {
            throw new BusinessException(401, "管理员未登录");
        }
        if (reportId == null) {
            throw new BusinessException(400, "工单ID不能为空");
        }
        if (request == null || !StringUtils.hasText(request.getAction())) {
            throw new BusinessException(400, "处理判定动作不能为空");
        }
        if (!StringUtils.hasText(request.getNote())) {
            throw new BusinessException(400, "处置说明不能为空");
        }

        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException(404, "举报工单不存在");
        }

        // 状态机校验：仅允许处理处于 PENDING 状态的工单
        if (!ReportStatus.PENDING.getCode().equals(report.getStatus())) {
            throw new BusinessException(400, "工单已被处理，不可重复处理");
        }

        String action = request.getAction().trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        String note = request.getNote().trim();

        if ("VALID".equals(action) || "ACCEPT".equals(action)) {
            // 1. 举报属实：更新工单状态
            report.setStatus(ReportStatus.HANDLED_VALID.getCode());
            report.setHandledBy(adminId);
            report.setHandledTime(now);
            report.setHandleResult(note);
            report.setUpdatedTime(now);
            reportMapper.updateById(report);

            // 2. 写入工单审核采纳的管理员审计流水
            adminAuditLogMapper.insert(AdminAuditLog.builder()
                    .adminId(adminId)
                    .adminUsername(adminUsername)
                    .operationType(AdminOperationType.PASS_REPORT.getCode())
                    .targetType("REPORT")
                    .targetId(report.getId())
                    .beforeStatus(ReportStatus.PENDING.getCode())
                    .afterStatus(ReportStatus.HANDLED_VALID.getCode())
                    .reason(note)
                    .ipAddress(ipAddress)
                    .createdTime(now)
                    .build());

            // 3. 执行目标实体治理动作与连带信用冲正
            executeGovernanceAction(adminId, adminUsername, report, note, ipAddress, now);

            log.info("管理员采纳举报工单并执行治理: adminId={}, reportId={}, targetType={}, targetId={}",
                    adminId, reportId, report.getTargetType(), report.getTargetId());

        } else if ("INVALID".equals(action) || "REJECT".equals(action)) {
            // 举报不属实：驳回工单
            report.setStatus(ReportStatus.HANDLED_INVALID.getCode());
            report.setHandledBy(adminId);
            report.setHandledTime(now);
            report.setHandleResult(note);
            report.setUpdatedTime(now);
            reportMapper.updateById(report);

            // 写入工单驳回审计流水
            adminAuditLogMapper.insert(AdminAuditLog.builder()
                    .adminId(adminId)
                    .adminUsername(adminUsername)
                    .operationType(AdminOperationType.REJECT_REPORT.getCode())
                    .targetType("REPORT")
                    .targetId(report.getId())
                    .beforeStatus(ReportStatus.PENDING.getCode())
                    .afterStatus(ReportStatus.HANDLED_INVALID.getCode())
                    .reason(note)
                    .ipAddress(ipAddress)
                    .createdTime(now)
                    .build());

            log.info("管理员驳回举报工单: adminId={}, reportId={}, targetType={}, targetId={}",
                    adminId, reportId, report.getTargetType(), report.getTargetId());

        } else {
            throw new BusinessException(400, "不支持的审核判定动作，请提交 VALID (采纳处置) 或 INVALID (驳回)");
        }

        return getReportDetail(reportId);
    }

    @Override
    public IPage<AdminAuditLogVO> pageAuditLogs(int page, int size) {
        int curPage = page > 0 ? page : 1;
        int pageSize = (size > 0 && size <= 100) ? size : 10;

        Page<AdminAuditLog> p = new Page<>(curPage, pageSize);
        LambdaQueryWrapper<AdminAuditLog> wrapper = new LambdaQueryWrapper<AdminAuditLog>()
                .orderByDesc(AdminAuditLog::getCreatedTime);

        IPage<AdminAuditLog> logPage = adminAuditLogMapper.selectPage(p, wrapper);
        return logPage.convert(this::convertToAuditLogVO);
    }

    /**
     * 治理执行核心方法：对违规目标实施下架/屏蔽/冻结与信用冲正
     */
    private void executeGovernanceAction(
            Long adminId,
            String adminUsername,
            Report report,
            String note,
            String ipAddress,
            LocalDateTime now
    ) {
        String targetType = report.getTargetType();
        Long targetId = report.getTargetId();

        if (ReportTargetType.GOODS.getCode().equals(targetType)) {
            Goods goods = goodsMapper.selectById(targetId);
            if (goods != null) {
                String beforeStatus = goods.getStatus();
                goods.setStatus("OFF_SHELF");
                goods.setUpdatedTime(now);
                goodsMapper.updateById(goods);

                adminAuditLogMapper.insert(AdminAuditLog.builder()
                        .adminId(adminId)
                        .adminUsername(adminUsername)
                        .operationType(AdminOperationType.OFF_SHELF_GOODS.getCode())
                        .targetType("GOODS")
                        .targetId(goods.getId())
                        .beforeStatus(beforeStatus)
                        .afterStatus("OFF_SHELF")
                        .reason(note)
                        .ipAddress(ipAddress)
                        .createdTime(now)
                        .build());
                log.info("治理动作: 违规商品下架 goodsId={}, beforeStatus={}", goods.getId(), beforeStatus);
            }
        } else if (ReportTargetType.REVIEW.getCode().equals(targetType)) {
            Review review = reviewMapper.selectById(targetId);
            if (review != null) {
                String beforeStatus = review.getStatus() != null ? review.getStatus().name() : "VISIBLE";
                review.setStatus(ReviewStatus.AUDIT_REJECTED);
                review.setUpdatedTime(now);
                reviewMapper.updateById(review);

                // 信用安全冲正 (Credit Reversal)
                reversalReviewCredit(review, note);

                adminAuditLogMapper.insert(AdminAuditLog.builder()
                        .adminId(adminId)
                        .adminUsername(adminUsername)
                        .operationType(AdminOperationType.SHIELD_REVIEW.getCode())
                        .targetType("REVIEW")
                        .targetId(review.getId())
                        .beforeStatus(beforeStatus)
                        .afterStatus(ReviewStatus.AUDIT_REJECTED.name())
                        .reason(note)
                        .ipAddress(ipAddress)
                        .createdTime(now)
                        .build());
                log.info("治理动作: 违规评价屏蔽 reviewId={}, beforeStatus={}", review.getId(), beforeStatus);
            }
        } else if (ReportTargetType.USER.getCode().equals(targetType)) {
            User user = userMapper.selectById(targetId);
            if (user != null) {
                String beforeStatus = user.getStatus();
                user.setStatus("FROZEN");
                user.setUpdatedTime(now);
                userMapper.updateById(user);

                adminAuditLogMapper.insert(AdminAuditLog.builder()
                        .adminId(adminId)
                        .adminUsername(adminUsername)
                        .operationType(AdminOperationType.FREEZE_USER.getCode())
                        .targetType("USER")
                        .targetId(user.getId())
                        .beforeStatus(beforeStatus)
                        .afterStatus("FROZEN")
                        .reason(note)
                        .ipAddress(ipAddress)
                        .createdTime(now)
                        .build());
                log.info("治理动作: 违规用户冻结 userId={}, beforeStatus={}", user.getId(), beforeStatus);
            }
        }
    }

    /**
     * 评价违规屏蔽后的信用精准冲正
     */
    private void reversalReviewCredit(Review review, String note) {
        if (review == null || review.getReviewedUserId() == null || review.getScore() == null) {
            return;
        }
        int score = review.getScore();
        Long targetUserId = review.getReviewedUserId();
        Long reviewId = review.getId();

        // 5星原 +3 -> 冲正扣回 3 分
        if (score == 5) {
            creditService.deductCredit(
                    targetUserId,
                    3,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW",
                    reviewId,
                    "违规好评被管理员屏蔽，追缴信用分 (" + note + ")"
            );
        }
        // 4星原 +1 -> 冲正扣回 1 分
        else if (score == 4) {
            creditService.deductCredit(
                    targetUserId,
                    1,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW",
                    reviewId,
                    "违规好评被管理员屏蔽，追缴信用分 (" + note + ")"
            );
        }
        // 2星原 -2 -> 冲正补回 2 分
        else if (score == 2) {
            creditService.addCredit(
                    targetUserId,
                    2,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW",
                    reviewId,
                    "违规差评被管理员屏蔽，恢复信用分 (" + note + ")"
            );
        }
        // 1星原 -5 -> 冲正补回 5 分
        else if (score == 1) {
            creditService.addCredit(
                    targetUserId,
                    5,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW",
                    reviewId,
                    "违规差评被管理员屏蔽，恢复信用分 (" + note + ")"
            );
        }
        // 3星原变动 0 分 -> 无需冲正
    }

    /**
     * 转换为管理员详情视图对象并组装目标业务快照
     */
    private AdminReportDetailVO convertToAdminDetailVO(Report report) {
        if (report == null) {
            return null;
        }

        String reasonDesc = report.getReasonType();
        try {
            reasonDesc = ReportReasonType.valueOf(report.getReasonType()).getDescription();
        } catch (Exception ignored) {
        }

        String statusDesc = report.getStatus();
        try {
            statusDesc = ReportStatus.valueOf(report.getStatus()).getDescription();
        } catch (Exception ignored) {
        }

        // 关联查询举报人与处理人基础信息
        String reporterUsername = null;
        String reporterNickname = null;
        if (report.getReporterId() != null) {
            User reporter = userMapper.selectById(report.getReporterId());
            if (reporter != null) {
                reporterUsername = reporter.getUsername();
                reporterNickname = reporter.getNickname();
            }
        }

        String handlerUsername = null;
        if (report.getHandledBy() != null) {
            User handler = userMapper.selectById(report.getHandledBy());
            if (handler != null) {
                handlerUsername = handler.getUsername();
            }
        }

        // 组装目标实体业务上下文快照
        Map<String, Object> targetSnapshot = buildTargetSnapshot(report.getTargetType(), report.getTargetId());

        return AdminReportDetailVO.builder()
                .id(report.getId())
                .reporterId(report.getReporterId())
                .reporterUsername(reporterUsername)
                .reporterNickname(reporterNickname)
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reasonType(report.getReasonType())
                .reasonDesc(reasonDesc)
                .description(report.getDescription())
                .evidenceImages(report.getEvidenceImages())
                .status(report.getStatus())
                .statusDesc(statusDesc)
                .handledBy(report.getHandledBy())
                .handlerUsername(handlerUsername)
                .handleResult(report.getHandleResult())
                .handledTime(report.getHandledTime())
                .createdTime(report.getCreatedTime())
                .targetSnapshot(targetSnapshot)
                .build();
    }

    private Map<String, Object> buildTargetSnapshot(String targetType, Long targetId) {
        Map<String, Object> snapshot = new HashMap<>();
        if (targetId == null) {
            return snapshot;
        }

        if (ReportTargetType.GOODS.getCode().equals(targetType)) {
            Goods goods = goodsMapper.selectById(targetId);
            if (goods != null) {
                snapshot.put("goodsId", goods.getId());
                snapshot.put("title", goods.getTitle());
                snapshot.put("price", goods.getPrice());
                snapshot.put("status", goods.getStatus());
                snapshot.put("sellerId", goods.getSellerId());
            }
        } else if (ReportTargetType.REVIEW.getCode().equals(targetType)) {
            Review review = reviewMapper.selectById(targetId);
            if (review != null) {
                snapshot.put("reviewId", review.getId());
                snapshot.put("score", review.getScore());
                snapshot.put("content", review.getContent());
                snapshot.put("status", review.getStatus() != null ? review.getStatus().name() : null);
                snapshot.put("reviewerId", review.getReviewerId());
                snapshot.put("reviewedUserId", review.getReviewedUserId());
            }
        } else if (ReportTargetType.USER.getCode().equals(targetType)) {
            User user = userMapper.selectById(targetId);
            if (user != null) {
                snapshot.put("userId", user.getId());
                snapshot.put("username", user.getUsername());
                snapshot.put("nickname", user.getNickname());
                snapshot.put("status", user.getStatus());
                snapshot.put("role", user.getRole());
            }
        }
        return snapshot;
    }

    private AdminAuditLogVO convertToAuditLogVO(AdminAuditLog logEntity) {
        if (logEntity == null) {
            return null;
        }
        String opDesc = logEntity.getOperationType();
        try {
            opDesc = AdminOperationType.valueOf(logEntity.getOperationType()).getDescription();
        } catch (Exception ignored) {
        }

        return AdminAuditLogVO.builder()
                .id(logEntity.getId())
                .adminId(logEntity.getAdminId())
                .adminUsername(logEntity.getAdminUsername())
                .operationType(logEntity.getOperationType())
                .operationDesc(opDesc)
                .targetType(logEntity.getTargetType())
                .targetId(logEntity.getTargetId())
                .beforeStatus(logEntity.getBeforeStatus())
                .afterStatus(logEntity.getAfterStatus())
                .reason(logEntity.getReason())
                .ipAddress(logEntity.getIpAddress())
                .createdTime(logEntity.getCreatedTime())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO restoreReview(Long adminId, String adminUsername, Long reviewId, String reason, String ipAddress) {
        if (adminId == null || adminUsername == null) {
            throw new BusinessException(401, "管理员未登录");
        }
        if (reviewId == null) {
            throw new BusinessException(400, "评价ID不能为空");
        }

        // 1. 检查评价存在性
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "评价不存在");
        }

        // 2. 状态机前置拦截: 仅允许 AUDIT_REJECTED -> VISIBLE
        if (review.getStatus() != ReviewStatus.AUDIT_REJECTED) {
            throw new BusinessException(400, "评价当前处于正常展示状态，无需重复恢复");
        }

        // 3. 数据库原子条件更新，防止并发恢复竞态
        int affected = reviewMapper.restoreReviewAtomic(reviewId);
        if (affected <= 0) {
            throw new BusinessException(409, "评价已被恢复或状态已发生变更");
        }

        String note = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "管理员审核恢复";
        LocalDateTime now = LocalDateTime.now();

        // 4. 信用精准反向补偿 (Credit Restoration)
        restoreReviewCredit(review, note);

        // 5. 写入管理员审计日志
        adminAuditLogMapper.insert(AdminAuditLog.builder()
                .adminId(adminId)
                .adminUsername(adminUsername)
                .operationType(AdminOperationType.RESTORE_REVIEW.getCode())
                .targetType("REVIEW")
                .targetId(review.getId())
                .beforeStatus(ReviewStatus.AUDIT_REJECTED.name())
                .afterStatus(ReviewStatus.VISIBLE.name())
                .reason(note)
                .ipAddress(ipAddress)
                .createdTime(now)
                .build());

        log.info("管理员治理动作: 评价解除屏蔽并恢复展示 reviewId={}, adminId={}", reviewId, adminId);

        // 6. 重新查出最新实体组装 VO
        Review updatedReview = reviewMapper.selectById(reviewId);
        return ReviewVO.builder()
                .id(updatedReview.getId())
                .orderId(updatedReview.getOrderId())
                .goodsId(updatedReview.getGoodsId())
                .reviewerId(updatedReview.getReviewerId())
                .reviewedUserId(updatedReview.getReviewedUserId())
                .score(updatedReview.getScore())
                .content(updatedReview.getContent())
                .status(updatedReview.getStatus().name())
                .likeCount(updatedReview.getLikeCount() != null ? updatedReview.getLikeCount() : 0)
                .likedByCurrentUser(false)
                .createdTime(updatedReview.getCreatedTime())
                .build();
    }

    /**
     * 评价恢复后的信用精准反向补偿
     */
    private void restoreReviewCredit(Review review, String note) {
        if (review == null || review.getReviewedUserId() == null || review.getScore() == null) {
            return;
        }
        int score = review.getScore();
        Long targetUserId = review.getReviewedUserId();
        Long reviewId = review.getId();

        // 5星好评恢复: +3 分
        if (score == 5) {
            creditService.addCredit(
                    targetUserId,
                    3,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW_RESTORE",
                    reviewId,
                    "管理员恢复合规好评，恢复信用分 (" + note + ")"
            );
        }
        // 4星好评恢复: +1 分
        else if (score == 4) {
            creditService.addCredit(
                    targetUserId,
                    1,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW_RESTORE",
                    reviewId,
                    "管理员恢复合规好评，恢复信用分 (" + note + ")"
            );
        }
        // 2星差评恢复: -2 分
        else if (score == 2) {
            creditService.deductCredit(
                    targetUserId,
                    2,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW_RESTORE",
                    reviewId,
                    "管理员恢复差评展示，重新扣减信用分 (" + note + ")"
            );
        }
        // 1星差评恢复: -5 分
        else if (score == 1) {
            creditService.deductCredit(
                    targetUserId,
                    5,
                    CreditChangeType.ADMIN_ADJUST,
                    "REVIEW_RESTORE",
                    reviewId,
                    "管理员恢复差评展示，重新扣减信用分 (" + note + ")"
            );
        }
        // 3星评价: 0 分，无需调整
    }
}
