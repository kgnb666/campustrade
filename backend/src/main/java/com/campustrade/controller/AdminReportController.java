package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.report.ReportQueryRequest;
import com.campustrade.dto.review.RestoreReviewRequest;
import com.campustrade.entity.User;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.vo.report.AdminAuditLogVO;
import com.campustrade.vo.report.AdminReportDetailVO;
import com.campustrade.vo.review.ReviewVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 平台管理员治理与工单处理 REST API 控制器
 * 严格基于 Spring Security 角色校验 (ROLE_ADMIN)
 */
@Slf4j
@RestController
@RequestMapping({"/admin", "/api/admin"})
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminGovernanceService adminGovernanceService;

    /**
     * 分页查询平台举报工单列表
     * GET /admin/reports
     */
    @GetMapping("/reports")
    public Result<IPage<AdminReportDetailVO>> pageReports(ReportQueryRequest queryRequest) {
        IPage<AdminReportDetailVO> result = adminGovernanceService.pageReports(queryRequest);
        return Result.success(result);
    }

    /**
     * 获取举报工单详情 (含目标实体快照)
     * GET /admin/reports/{id}
     */
    @GetMapping("/reports/{id}")
    public Result<AdminReportDetailVO> getReportDetail(@PathVariable("id") Long id) {
        AdminReportDetailVO detail = adminGovernanceService.getReportDetail(id);
        return Result.success(detail);
    }

    /**
     * 审核处理举报工单并实施治理下架/屏蔽/冻结
     * PUT /admin/reports/{id}/handle
     */
    @PutMapping("/reports/{id}/handle")
    public Result<AdminReportDetailVO> handleReport(
            @CurrentUser User admin,
            @PathVariable("id") Long id,
            @Valid @RequestBody HandleReportRequest request,
            HttpServletRequest httpRequest
    ) {
        String ipAddress = getClientIp(httpRequest);
        AdminReportDetailVO result = adminGovernanceService.handleReport(
                admin.getId(),
                admin.getUsername(),
                id,
                request,
                ipAddress
        );
        return Result.success("举报工单处理完成", result);
    }

    /**
     * 管理员恢复被屏蔽的评价并补偿信用分
     * PUT /admin/reviews/{id}/restore
     */
    @PutMapping("/reviews/{id}/restore")
    public Result<ReviewVO> restoreReview(
            @CurrentUser User admin,
            @PathVariable("id") Long id,
            @RequestBody(required = false) RestoreReviewRequest request,
            HttpServletRequest httpRequest
    ) {
        String ipAddress = getClientIp(httpRequest);
        String reason = (request != null && request.getReason() != null) ? request.getReason() : "管理员审核恢复";
        ReviewVO result = adminGovernanceService.restoreReview(
                admin.getId(),
                admin.getUsername(),
                id,
                reason,
                ipAddress
        );
        return Result.success("评价已成功解除屏蔽并恢复展示", result);
    }

    /**
     * 分页查询管理员操作审计流水
     * GET /admin/audit-logs 或 GET /admin/reports/audit-logs
     */
    @GetMapping({"/audit-logs", "/reports/audit-logs"})
    public Result<IPage<AdminAuditLogVO>> pageAuditLogs(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size
    ) {
        IPage<AdminAuditLogVO> result = adminGovernanceService.pageAuditLogs(page, size);
        return Result.success(result);
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (StringUtils.hasText(ip) && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
