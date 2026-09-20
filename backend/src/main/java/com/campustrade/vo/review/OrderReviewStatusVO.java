package com.campustrade.vo.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单双向评价状态视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderReviewStatusVO implements Serializable {

    private Long orderId;
    private Boolean isBuyer;
    private Boolean canReview;
    private String reasonIfNotEligible;
    private ReviewVO myReview;
    private ReviewVO peerReview;
}
