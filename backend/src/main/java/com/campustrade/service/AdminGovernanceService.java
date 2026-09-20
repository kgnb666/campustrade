package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.report.ReportQueryRequest;
import com.campustrade.vo.report.AdminAuditLogVO;
import com.campustrade.vo.report.AdminReportDetailVO;
import com.campustrade.vo.review.ReviewVO;

/**
 * 平台管理员治理与工单处理服务接口
 */
public interface AdminGovernanceService {

    /**
     * 分页查询举报工单列表
     *
     * @param queryRequest 筛选条件与分页入参
     * @return 举报工单详细视图分页
     */
    IPage<AdminReportDetailVO> pageReports(ReportQueryRequest queryRequest);

    /**
     * 获取指定举报工单详情 (含目标实体快照)
     *
     * @param reportId 工单ID
     * @return 详细工单视图
     */
    AdminReportDetailVO getReportDetail(Long reportId);

    /**
     * 审核处理举报工单并执行治理动作
     *
     * @param adminId       处理管理员ID
     * @param adminUsername 处理管理员账号
     * @param reportId      工单ID
     * @param request       处理入参
     * @param ipAddress     客户端IP
     * @return 处理后的工单详细视图
     */
    AdminReportDetailVO handleReport(Long adminId, String adminUsername, Long reportId, HandleReportRequest request, String ipAddress);

    /**
     * 分页查询管理员操作审计日志
     *
     * @param page 页码
     * @param size 每页大小
     * @return 审计日志分页列表
     */
    IPage<AdminAuditLogVO> pageAuditLogs(int page, int size);

    /**
     * 管理员恢复被屏蔽的评价并补偿信用分
     *
     * @param adminId       处理管理员ID
     * @param adminUsername 处理管理员账号
     * @param reviewId      评价ID
     * @param reason        恢复原因说明
     * @param ipAddress     客户端IP
     * @return 恢复后的评价详情视图
     */
    ReviewVO restoreReview(Long adminId, String adminUsername, Long reviewId, String reason, String ipAddress);
}
