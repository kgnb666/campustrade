package com.campustrade.vo.report;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户端举报工单视图展示对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportVO implements Serializable {

    private Long id;

    private String targetType;

    private Long targetId;

    private String reasonType;

    private String reasonDesc;

    private String description;

    private String evidenceImages;

    private String status;

    private String statusDesc;

    private String handleResult;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handledTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;
}
