package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.entity.User;
import com.campustrade.service.ReportService;
import com.campustrade.vo.report.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户端举报工单 REST API 控制器
 */
@Slf4j
@RestController
@RequestMapping({"/reports", "/api/reports"})
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 提交举报工单
     * POST /reports
     */
    @PostMapping
    public Result<ReportVO> submitReport(@CurrentUser User user, @Valid @RequestBody CreateReportRequest request) {
        ReportVO vo = reportService.submitReport(user.getId(), request);
        return Result.success("举报提交成功，平台将尽快审核处理", vo);
    }

    /**
     * 查询我的举报记录列表
     * GET /reports/my
     */
    @GetMapping("/my")
    public Result<IPage<ReportVO>> listMyReports(
            @CurrentUser User user,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size
    ) {
        IPage<ReportVO> result = reportService.listMyReports(user.getId(), page, size);
        return Result.success(result);
    }
}
