package com.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.vo.review.OrderReviewStatusVO;
import com.campustrade.vo.review.ReviewLikeVO;
import com.campustrade.vo.review.ReviewVO;

/**
 * 评价领域核心业务接口
 */
public interface ReviewService {

    /**
     * 创建交易评价
     *
     * @param currentUserId 当前登录用户ID
     * @param request       评价请求入参
     * @return 评价视图对象 VO
     */
    ReviewVO createReview(Long currentUserId, CreateReviewRequest request);

    /**
     * 分页查询用户收到的公开评价 (支持传入当前查看人进行点赞态关联)
     *
     * <p>只保留这一个重载：当前查看人由调用方（Controller）显式传入，服务层不从
     * {@code SecurityContext} 隐式取值——隐式取值会派生出一个"看起来等价、实则行为不同"
     * 的重载（无登录态时静默为 null），两套入口并存只会让调用方误选。</p>
     *
     * @param userId        目标用户ID
     * @param page          页码 (>= 1)
     * @param size          每页大小 (1 ~ 50)
     * @param currentUserId 当前登录用户ID (可为 null)
     * @return 分页结果
     */
    IPage<ReviewVO> getReviewsByUser(Long userId, Integer page, Integer size, Long currentUserId);

    /**
     * 分页查询商品收到的公开评价 (支持传入当前查看人进行点赞态关联)
     *
     * <p>重载收敛理由同 {@link #getReviewsByUser(Long, Integer, Integer, Long)}。</p>
     *
     * @param goodsId       目标商品ID
     * @param page          页码 (>= 1)
     * @param size          每页大小 (1 ~ 50)
     * @param currentUserId 当前登录用户ID (可为 null)
     * @return 分页结果
     */
    IPage<ReviewVO> getReviewsByGoods(Long goodsId, Integer page, Integer size, Long currentUserId);

    /**
     * 查询订单的双向评价状态与详情
     *
     * @param orderId       订单ID
     * @param currentUserId 当前查询人ID
     * @return 订单双向评价状态 VO
     */
    OrderReviewStatusVO getReviewByOrder(Long orderId, Long currentUserId);

    /**
     * 点赞评价
     *
     * @param currentUserId 当前登录用户ID
     * @param reviewId      评价ID
     * @return 点赞状态与计数 VO
     */
    ReviewLikeVO likeReview(Long currentUserId, Long reviewId);

    /**
     * 取消点赞评价
     *
     * @param currentUserId 当前登录用户ID
     * @param reviewId      评价ID
     * @return 点赞状态与计数 VO
     */
    ReviewLikeVO unlikeReview(Long currentUserId, Long reviewId);

    /**
     * 查询指定评价的点赞状态与点赞总数
     *
     * @param currentUserId 当前登录用户ID (可为 null)
     * @param reviewId      评价ID
     * @return 点赞状态与计数 VO
     */
    ReviewLikeVO getLikeStatus(Long currentUserId, Long reviewId);
}
