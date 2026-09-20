package com.campustrade.vo.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 评价点赞操作与状态视图对象 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewLikeVO implements Serializable {

    private Long reviewId;
    private Boolean liked;
    private Integer likeCount;
}
