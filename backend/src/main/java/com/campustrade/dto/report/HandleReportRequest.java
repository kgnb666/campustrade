package com.campustrade.dto.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 管理员处理举报工单请求入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandleReportRequest implements Serializable {

    /**
     * 审核判定动作:
     * VALID / ACCEPT: 举报属实，执行平台治理
     * INVALID / REJECT: 举报不属实，予以驳回
     */
    @NotBlank(message = "审核判定动作不能为空")
    private String action;

    /**
     * 处理回复/处置结论说明 (最多500字)
     */
    @NotBlank(message = "处置说明不能为空")
    @Size(max = 500, message = "处置说明不能超过500字")
    private String note;
}
