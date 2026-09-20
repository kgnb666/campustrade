package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 高校学校实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("campus_school")
public class CampusSchool implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("school_name")
    private String schoolName;

    @TableField("school_code")
    private String schoolCode;

    @TableField("email_suffix")
    private String emailSuffix;

    @Builder.Default
    private String status = "ACTIVE";

    @TableField("created_time")
    private LocalDateTime createdTime;
}
