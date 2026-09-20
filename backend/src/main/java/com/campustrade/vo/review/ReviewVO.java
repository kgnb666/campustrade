package com.campustrade.vo.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价视图对象 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewVO implements Serializable {

    private Long id;
    private Long orderId;
    private Long goodsId;
    private String goodsTitle;
    private Long reviewerId;
    private String reviewerNickname;
    private String reviewerAvatar;
    private Long reviewedUserId;
    private Integer score;
    private String content;
    private List<String> tags;
    private Boolean isAnonymous;
    private String status;
    private Integer likeCount;
    private Boolean likedByCurrentUser;
    private LocalDateTime createdTime;
}
