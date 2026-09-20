package com.campustrade.vo.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理员操作审计流水视图展示对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLogVO implements Serializable {

    private Long id;

    private Long adminId;

    private String adminUsername;

    private String operationType;

    private String operationDesc;

    private String targetType;

    private Long targetId;

    private String beforeStatus;

    private String afterStatus;

    private String reason;

    private String ipAddress;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;
}
