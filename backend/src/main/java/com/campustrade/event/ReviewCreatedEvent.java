package com.campustrade.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 评价创建领域事件
 */
@Getter
@Builder
@ToString
@AllArgsConstructor
public class ReviewCreatedEvent implements Serializable {

    private final Long reviewId;
    private final Long orderId;
    private final Long goodsId;
    private final Long reviewerId;
    private final Long reviewedUserId;
    private final Integer score;
}
