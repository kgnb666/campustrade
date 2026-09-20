package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.constant.CreditRule;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

        // 批量聚合关联数据后一次性转换：
        // 旧实现是 reportPage.convert(this::convertToAdminDetailVO)，每行都要单独查举报人、
        // 处理人与目标快照，pageSize=100 时单请求最多产生 3×100 条关联查询（N+1）。
        // 现在改为"先按需收集 ID -> 每种关联批量查一次 -> 内存组装"，SQL 条数与页大小解耦。
        ReportViewContext context = buildViewContext(reportPage.getRecords());
        List<AdminReportDetailVO> voList = reportPage.getRecords().stream()
                .map(report -> convertToAdminDetailVO(report, context))
                .collect(Collectors.toList());

        Page<AdminReportDetailVO> resultPage =
                new Page<>(reportPage.getCurrent(), reportPage.getSize(), reportPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
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
        return convertToAdminDetailVO(report, buildViewContext(Collections.singletonList(report)));
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
            // 1. 举报属实：原子条件更新工单状态（UPDATE ... WHERE id=? AND status='PENDING'）。
            //    这是并发处理同一工单的唯一有效性判定：两个管理员同时处理时，后到者的 UPDATE
            //    会因 status 已不是 PENDING 而命中 0 行，直接抛 409 并整体回滚，
            //    因此结论只落一次、审计只增一条、治理动作与信用追缴也只执行一次。
            requireReportPendingUpdated(reportId, adminId,
                    reportMapper.handleReportAtomic(reportId, ReportStatus.HANDLED_VALID.getCode(), adminId, now, note));

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
            // 举报不属实：原子条件更新驳回工单（并发语义同上）
            requireReportPendingUpdated(reportId, adminId,
                    reportMapper.handleReportAtomic(reportId, ReportStatus.HANDLED_INVALID.getCode(), adminId, now, note));

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

    /**
     * 校验"原子条件处理工单"的结果：0 行受影响说明工单已被其他管理员处理。
     *
     * <p>此处抛异常会回滚本事务，从而保证审计流水与治理动作都不会由失败方重复写入。</p>
     */
    private void requireReportPendingUpdated(Long reportId, Long adminId, int affected) {
        if (affected <= 0) {
            log.warn("并发处理同一举报工单被原子条件更新拦截，本次处理未生效: reportId={}, losingAdminId={}", reportId, adminId);
            throw new BusinessException(409, "该工单已被其他管理员处理，请刷新后查看最新状态");
        }
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
                // 定点更新 + 前置条件：只改 status/updated_time，绝不整行回写（否则会覆盖并发浏览量/价格修改）
                int affected = goodsMapper.offShelfForGovernance(goods.getId());
                if (affected <= 0) {
                    log.warn("治理下架未生效（商品已处于 {} 状态，无需重复下架）: goodsId={}, reportId={}",
                            beforeStatus, goods.getId(), report.getId());
                } else {
                    adminAuditLogMapper.insert(AdminAuditLog.builder()
                            .adminId(adminId)
                            .adminUsername(adminUsername)
                            .operationType(AdminOperationType.OFF_SHELF_GOODS.getCode())
                            .targetType("GOODS")
                            .targetId(goods.getId())
                            .beforeStatus(beforeStatus)
                            .afterStatus(GoodsStatus.OFF_SHELF.getCode())
                            .reason(note)
                            .ipAddress(ipAddress)
                            .createdTime(now)
                            .build());
                    log.info("治理动作: 违规商品下架 goodsId={}, beforeStatus={}", goods.getId(), beforeStatus);
                }
            }
        } else if (ReportTargetType.REVIEW.getCode().equals(targetType)) {
            Review review = reviewMapper.selectById(targetId);
            if (review != null) {
                String beforeStatus = review.getStatus() != null ? review.getStatus().name() : "VISIBLE";
                // 定点更新 + 前置条件：VISIBLE -> AUDIT_REJECTED 的原子跃迁（与 restoreReviewAtomic 对称）
                int affected = reviewMapper.shieldReviewAtomic(review.getId());
                if (affected <= 0) {
                    log.warn("治理屏蔽未生效（评价当前状态 {}，可能已被其他管理员屏蔽）: reviewId={}, reportId={}",
                            beforeStatus, review.getId(), report.getId());
                } else {
                    // 先落审计日志，再用"审计日志主键"作为本次治理动作的唯一标识参与信用幂等键：
                    // 同一动作重试 → 键相同 → 不重复追缴；不同次动作（屏蔽→恢复→再次屏蔽）→ 键不同 → 各自生效
                    AdminAuditLog auditLog = AdminAuditLog.builder()
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
                            .build();
                    adminAuditLogMapper.insert(auditLog);

                    reversalReviewCredit(review, note, governanceActionKey(AdminOperationType.SHIELD_REVIEW.getCode(), auditLog.getId()));

                    log.info("治理动作: 违规评价屏蔽 reviewId={}, beforeStatus={}, actionKey={}",
                            review.getId(), beforeStatus, governanceActionKey(AdminOperationType.SHIELD_REVIEW.getCode(), auditLog.getId()));
                }
            }
        } else if (ReportTargetType.USER.getCode().equals(targetType)) {
            User user = userMapper.selectById(targetId);
            if (user != null) {
                String beforeStatus = user.getStatus();
                // 定点更新 + 前置条件：只改 status/updated_time
                int affected = userMapper.freezeForGovernance(user.getId());
                if (affected <= 0) {
                    log.warn("治理冻结未生效（用户已处于 {} 状态）: userId={}, reportId={}",
                            beforeStatus, user.getId(), report.getId());
                } else {
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
    }

    /**
     * 构造治理动作的幂等标识：{@code 操作类型@AUDIT:审计日志ID}。
     *
     * <p>每一次真正生效的治理动作都会且只会写入一条 {@code admin_audit_log}（状态跃迁是原子条件更新，
     * 未被跃迁的分支不会落审计），因此审计日志主键天然就是"这次动作"的唯一标识。</p>
     */
    private String governanceActionKey(String operationType, Long auditLogId) {
        return operationType + "@AUDIT:" + auditLogId;
    }

    /**
     * 评价违规屏蔽后的信用精准冲正。
     *
     * <p>冲正幅度 = -（该星级原本产生的信用变动），数值来自 {@link CreditRule}；
     * 方向（追缴/补回）由 {@link CreditService#applyDelta} 按符号落地，
     * 因此"屏蔽冲正"与"评价创建加分"必然互为精确逆操作，不会出现某一边单独改数值后的错配。</p>
     */
    private void reversalReviewCredit(Review review, String note, String actionKey) {
        if (review == null || review.getReviewedUserId() == null || review.getScore() == null) {
            return;
        }
        int score = review.getScore();
        if (!CreditRule.isKnownStar(score)) {
            log.warn("评价星级不在信用规则取值域内，跳过冲正: reviewId={}, score={}", review.getId(), score);
            return;
        }

        int delta = CreditRule.reviewReversalDeltaForScore(score);
        if (delta == 0) {
            log.info("{}星评价原变动为 0，屏蔽无需冲正: reviewId={}, reviewedUserId={}",
                    score, review.getId(), review.getReviewedUserId());
            return;
        }

        creditService.applyDelta(
                review.getReviewedUserId(),
                delta,
                CreditChangeType.ADMIN_ADJUST,
                "REVIEW",
                review.getId(),
                (delta < 0 ? "违规好评被管理员屏蔽，追缴信用分 (" : "违规差评被管理员屏蔽，恢复信用分 (") + note + ")",
                actionKey
        );
    }

    /**
     * 转换为管理员详情视图对象并组装目标业务快照（使用批量聚合上下文，不再产生每行查询）。
     *
     * @param report  工单实体
     * @param context 由 {@link #buildViewContext(List)} 一次性构建的关联数据快照
     */
    private AdminReportDetailVO convertToAdminDetailVO(Report report, ReportViewContext context) {
        if (report == null) {
            return null;
        }

        String reasonDesc = report.getReasonType();
        try {
            reasonDesc = ReportReasonType.valueOf(report.getReasonType()).getDescription();
        } catch (Exception e) {
            // 枚举值域外的存量数据（历史造数/人工改库）：回退为原始字面量，但必须留痕
            log.debug("举报原因类型不在枚举值域内，回退展示原始值: reasonType={}, msg={}",
                    report.getReasonType(), e.getMessage());
        }

        String statusDesc = report.getStatus();
        try {
            statusDesc = ReportStatus.valueOf(report.getStatus()).getDescription();
        } catch (Exception e) {
            log.debug("举报状态不在枚举值域内，回退展示原始值: status={}, msg={}",
                    report.getStatus(), e.getMessage());
        }

        // 关联信息全部来自批量上下文（内存查表，零 SQL）
        User reporter = context.user(report.getReporterId());
        String reporterUsername = null;
        String reporterNickname = null;
        if (reporter != null) {
            reporterUsername = reporter.getUsername();
            reporterNickname = reporter.getNickname();
        }

        User handler = context.user(report.getHandledBy());
        String handlerUsername = (handler != null) ? handler.getUsername() : null;

        // 组装目标实体业务上下文快照
        Map<String, Object> targetSnapshot = context.targetSnapshot(report.getTargetType(), report.getTargetId());

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

    /**
     * 批量构建转换所需的关联数据上下文（把 N+1 收敛为常数条查询）。
     *
     * <p>查询次数上界固定为 3：</p>
     * <ol>
     *   <li>{@code user}：举报人 ∪ 处理人 ∪ 被举报用户合并成一次 {@code selectBatchIds}；</li>
     *   <li>{@code goods}：targetType=GOODS 的 targetId 一次批量查；</li>
     *   <li>{@code review}：targetType=REVIEW 的 targetId 一次批量查。</li>
     * </ol>
     * <p>空集合的桶直接跳过（不产生 SQL），因此只有一种 targetType 的页面通常只多 2 条查询。</p>
     */
    private ReportViewContext buildViewContext(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return ReportViewContext.empty();
        }

        Set<Long> userIds = new HashSet<>();
        Set<Long> goodsIds = new HashSet<>();
        Set<Long> reviewIds = new HashSet<>();
        for (Report report : reports) {
            if (report.getReporterId() != null) {
                userIds.add(report.getReporterId());
            }
            if (report.getHandledBy() != null) {
                userIds.add(report.getHandledBy());
            }
            Long targetId = report.getTargetId();
            if (targetId == null) {
                continue;
            }
            if (ReportTargetType.USER.getCode().equals(report.getTargetType())) {
                // 被举报用户与举报人/处理人同表，合并进同一次批量查询
                userIds.add(targetId);
            } else if (ReportTargetType.GOODS.getCode().equals(report.getTargetType())) {
                goodsIds.add(targetId);
            } else if (ReportTargetType.REVIEW.getCode().equals(report.getTargetType())) {
                reviewIds.add(targetId);
            }
        }

        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(userIds);
            if (users != null) {
                for (User user : users) {
                    userMap.put(user.getId(), user);
                }
            }
        }

        Map<Long, Goods> goodsMap = new HashMap<>();
        if (!goodsIds.isEmpty()) {
            List<Goods> goodsList = goodsMapper.selectBatchIds(goodsIds);
            if (goodsList != null) {
                for (Goods goods : goodsList) {
                    goodsMap.put(goods.getId(), goods);
                }
            }
        }

        Map<Long, Review> reviewMap = new HashMap<>();
        if (!reviewIds.isEmpty()) {
            List<Review> reviews = reviewMapper.selectBatchIds(reviewIds);
            if (reviews != null) {
                for (Review review : reviews) {
                    reviewMap.put(review.getId(), review);
                }
            }
        }

        return new ReportViewContext(userMap, goodsMap, reviewMap);
    }

    /**
     * 工单列表/详情的关联数据上下文：只做内存查表，绝不触发 SQL。
     *
     * <p>字段与 {@code buildTargetSnapshot} 老实现一一对应，因此返回给前端的
     * {@code targetSnapshot} 结构与取值规则完全不变。</p>
     */
    private static final class ReportViewContext {

        private final Map<Long, User> userMap;
        private final Map<Long, Goods> goodsMap;
        private final Map<Long, Review> reviewMap;

        private ReportViewContext(Map<Long, User> userMap, Map<Long, Goods> goodsMap, Map<Long, Review> reviewMap) {
            this.userMap = userMap;
            this.goodsMap = goodsMap;
            this.reviewMap = reviewMap;
        }

        static ReportViewContext empty() {
            return new ReportViewContext(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }

        User user(Long id) {
            return (id == null) ? null : userMap.get(id);
        }

        Map<String, Object> targetSnapshot(String targetType, Long targetId) {
            Map<String, Object> snapshot = new HashMap<>();
            if (targetId == null) {
                return snapshot;
            }

            if (ReportTargetType.GOODS.getCode().equals(targetType)) {
                Goods goods = goodsMap.get(targetId);
                if (goods != null) {
                    snapshot.put("goodsId", goods.getId());
                    snapshot.put("title", goods.getTitle());
                    snapshot.put("price", goods.getPrice());
                    snapshot.put("status", goods.getStatus());
                    snapshot.put("sellerId", goods.getSellerId());
                }
            } else if (ReportTargetType.REVIEW.getCode().equals(targetType)) {
                Review review = reviewMap.get(targetId);
                if (review != null) {
                    snapshot.put("reviewId", review.getId());
                    snapshot.put("score", review.getScore());
                    snapshot.put("content", review.getContent());
                    snapshot.put("status", review.getStatus() != null ? review.getStatus().name() : null);
                    snapshot.put("reviewerId", review.getReviewerId());
                    snapshot.put("reviewedUserId", review.getReviewedUserId());
                }
            } else if (ReportTargetType.USER.getCode().equals(targetType)) {
                User user = userMap.get(targetId);
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
    }

    private AdminAuditLogVO convertToAuditLogVO(AdminAuditLog logEntity) {
        if (logEntity == null) {
            return null;
        }
        String opDesc = logEntity.getOperationType();
        try {
            opDesc = AdminOperationType.valueOf(logEntity.getOperationType()).getDescription();
        } catch (Exception e) {
            log.debug("管理员操作类型不在枚举值域内，回退展示原始值: operationType={}, msg={}",
                    logEntity.getOperationType(), e.getMessage());
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

        // 4. 先写入管理员审计日志：它的主键即"本次恢复动作"的唯一标识，
        //    随后作为 actionKey 参与信用幂等键 —— 同一次恢复重试不重复补偿，
        //    而"再次屏蔽后第二次恢复"是新动作（新审计 ID），必须再次生效。
        AdminAuditLog auditLog = AdminAuditLog.builder()
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
                .build();
        adminAuditLogMapper.insert(auditLog);

        String actionKey = governanceActionKey(AdminOperationType.RESTORE_REVIEW.getCode(), auditLog.getId());

        // 5. 信用精准反向补偿 (Credit Restoration)
        restoreReviewCredit(review, note, actionKey);

        log.info("管理员治理动作: 评价解除屏蔽并恢复展示 reviewId={}, adminId={}, actionKey={}",
                reviewId, adminId, actionKey);

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
     * 评价恢复后的信用精准反向补偿。
     *
     * <p>补偿幅度 = 该星级原本产生的信用变动，数值来自 {@link CreditRule}：
     * {@link #reversalReviewCredit} 与本方法的幅度严格互为相反数，
     * 因此"屏蔽 → 恢复"这一轮治理动作对用户信用分的净影响恒为 0（不产生漂移）。</p>
     */
    private void restoreReviewCredit(Review review, String note, String actionKey) {
        if (review == null || review.getReviewedUserId() == null || review.getScore() == null) {
            return;
        }
        int score = review.getScore();
        if (!CreditRule.isKnownStar(score)) {
            log.warn("评价星级不在信用规则取值域内，跳过恢复补偿: reviewId={}, score={}", review.getId(), score);
            return;
        }

        int delta = CreditRule.reviewRestoreDeltaForScore(score);
        if (delta == 0) {
            log.info("{}星评价原变动为 0，恢复无需补偿: reviewId={}, reviewedUserId={}",
                    score, review.getId(), review.getReviewedUserId());
            return;
        }

        creditService.applyDelta(
                review.getReviewedUserId(),
                delta,
                CreditChangeType.ADMIN_ADJUST,
                "REVIEW_RESTORE",
                review.getId(),
                (delta > 0 ? "管理员恢复合规好评，恢复信用分 (" : "管理员恢复差评展示，重新扣减信用分 (") + note + ")",
                actionKey
        );
    }
}
