package com.campustrade.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.Result;
import com.campustrade.common.annotation.CurrentUser;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.User;
import com.campustrade.service.ReviewService;
import com.campustrade.vo.review.OrderReviewStatusVO;
import com.campustrade.vo.review.ReviewLikeVO;
import com.campustrade.vo.review.ReviewVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 评价领域 REST API 控制器
 */
@Slf4j
@RestController
@RequestMapping({"/reviews", "/api/reviews"})
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 接口1: 创建交易评价
     * POST /reviews
     */
    @PostMapping
    public Result<ReviewVO> createReview(@CurrentUser User user, @Valid @RequestBody CreateReviewRequest request) {
        ReviewVO vo = reviewService.createReview(user.getId(), request);
        return Result.success("评价发表成功", vo);
    }

    /**
     * 接口2: 查看指定用户收到的评价
     * GET /reviews/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public Result<IPage<ReviewVO>> getReviewsByUser(
            @PathVariable("userId") Long userId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @CurrentUser(required = false) User currentUser
    ) {
        Long currentUserId = currentUser != null ? currentUser.getId() : null;
        IPage<ReviewVO> result = reviewService.getReviewsByUser(userId, page, size, currentUserId);
        return Result.success(result);
    }

    /**
     * 接口3: 查看指定商品收到的评价
     * GET /reviews/goods/{goodsId}
     */
    @GetMapping("/goods/{goodsId}")
    public Result<IPage<ReviewVO>> getReviewsByGoods(
            @PathVariable("goodsId") Long goodsId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @CurrentUser(required = false) User currentUser
    ) {
        Long currentUserId = currentUser != null ? currentUser.getId() : null;
        IPage<ReviewVO> result = reviewService.getReviewsByGoods(goodsId, page, size, currentUserId);
        return Result.success(result);
    }

    /**
     * 接口4: 查询订单的双向评价状态
     * GET /reviews/order/{orderId}
     */
    @GetMapping("/order/{orderId}")
    public Result<OrderReviewStatusVO> getReviewByOrder(
            @CurrentUser User user,
            @PathVariable("orderId") Long orderId
    ) {
        OrderReviewStatusVO vo = reviewService.getReviewByOrder(orderId, user.getId());
        return Result.success(vo);
    }

    /**
     * 接口5: 评价点赞
     * POST /reviews/{reviewId}/like
     */
    @PostMapping("/{reviewId}/like")
    public Result<ReviewLikeVO> likeReview(
            @CurrentUser User user,
            @PathVariable("reviewId") Long reviewId
    ) {
        ReviewLikeVO vo = reviewService.likeReview(user.getId(), reviewId);
        return Result.success("点赞成功", vo);
    }

    /**
     * 接口6: 取消评价点赞
     * DELETE /reviews/{reviewId}/like
     */
    @DeleteMapping("/{reviewId}/like")
    public Result<ReviewLikeVO> unlikeReview(
            @CurrentUser User user,
            @PathVariable("reviewId") Long reviewId
    ) {
        ReviewLikeVO vo = reviewService.unlikeReview(user.getId(), reviewId);
        return Result.success("取消点赞成功", vo);
    }

    /**
     * 接口7: 查询评价点赞状态与计数
     * GET /reviews/{reviewId}/like
     */
    @GetMapping("/{reviewId}/like")
    public Result<ReviewLikeVO> getLikeStatus(
            @PathVariable("reviewId") Long reviewId,
            @CurrentUser(required = false) User currentUser
    ) {
        Long currentUserId = currentUser != null ? currentUser.getId() : null;
        ReviewLikeVO vo = reviewService.getLikeStatus(currentUserId, reviewId);
        return Result.success(vo);
    }
}
