package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.vo.report.ReportVO;

/**
 * 举报工单领域服务接口
 */
public interface ReportService {

    /**
     * 提交举报工单
     *
     * @param reporterId 举报人用户ID
     * @param request    举报提交参数
     * @return 举报工单信息
     */
    ReportVO submitReport(Long reporterId, CreateReportRequest request);

    /**
     * 分页查询当前用户的举报记录
     *
     * @param reporterId 举报人用户ID
     * @param page       页码
     * @param size       每页大小
     * @return 举报记录分页列表
     */
    IPage<ReportVO> listMyReports(Long reporterId, int page, int size);
}
