package com.campustrade.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户信用信息 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreditVO implements Serializable {

    private Integer creditScore;
    private Integer tradeCount;
    private Integer goodReviewCount;
    private Integer badReviewCount;
}
