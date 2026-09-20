package com.campustrade.dto.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 管理员恢复评价请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestoreReviewRequest implements Serializable {

    private String reason;
}
