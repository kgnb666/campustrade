package com.campustrade.vo.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理员端举报工单详细视图对象 (包含举报人信息与目标对象业务快照)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminReportDetailVO implements Serializable {

    private Long id;

    private Long reporterId;

    private String reporterUsername;

    private String reporterNickname;

    private String targetType;

    private Long targetId;

    private String reasonType;

    private String reasonDesc;

    private String description;

    private String evidenceImages;

    private String status;

    private String statusDesc;

    private Long handledBy;

    private String handlerUsername;

    private String handleResult;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handledTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    /**
     * 目标业务实体的上下文快照 (如商品标题/价格/卖家，或评价内容/星级/被评人，或违规用户账号信息)
     */
    private Map<String, Object> targetSnapshot;
}
