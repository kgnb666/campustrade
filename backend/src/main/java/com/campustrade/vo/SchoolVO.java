package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 高校学校信息 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchoolVO implements Serializable {

    private Long id;
    private String schoolName;
    private String schoolCode;
    private String emailSuffix;
}
