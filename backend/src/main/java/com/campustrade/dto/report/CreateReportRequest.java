package com.campustrade.dto.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 提交举报请求入参 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReportRequest implements Serializable {

    /**
     * 目标实体类型: GOODS, REVIEW, USER
     */
    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    /**
     * 目标实体ID
     */
    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    /**
     * 举报原因类型: FRAUD, COUNTERFEIT, ILLEGAL_PROHIBITED, HARASSMENT, MALICIOUS_REVIEW, OTHER
     */
    @NotBlank(message = "举报原因不能为空")
    private String reasonType;

    /**
     * 详细情况描述 (最多500字)
     */
    @Size(max = 500, message = "举报描述不能超过500字")
    private String description;

    /**
     * 证据图片 URL 列表 (逗号分隔或JSON字符串，最多1000字)
     */
    @Size(max = 1000, message = "证据图片链接不能超过1000字符")
    private String evidenceImages;
}
