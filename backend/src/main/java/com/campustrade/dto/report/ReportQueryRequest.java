package com.campustrade.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 管理员分页筛选举报工单入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportQueryRequest implements Serializable {

    /**
     * 工单状态筛选: PENDING, HANDLED_VALID, HANDLED_INVALID
     */
    private String status;

    /**
     * 目标类型筛选: GOODS, REVIEW, USER
     */
    private String targetType;

    /**
     * 页码 (从1开始，默认1)
     */
    @Builder.Default
    private Integer page = 1;

    /**
     * 每页数量 (默认10)
     */
    @Builder.Default
    private Integer size = 10;
}
