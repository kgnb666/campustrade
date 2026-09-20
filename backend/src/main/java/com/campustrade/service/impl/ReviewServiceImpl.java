package com.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.constant.CreditRule;
import com.campustrade.common.util.HtmlEscapeUtils;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Review;
import com.campustrade.entity.ReviewLike;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.event.ReviewCreatedEvent;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReviewLikeMapper;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.ReviewService;
import com.campustrade.vo.review.OrderReviewStatusVO;
import com.campustrade.vo.review.ReviewLikeVO;
import com.campustrade.vo.review.ReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评价领域业务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;
    private final ReviewLikeMapper reviewLikeMapper;
    private final TradeOrderMapper tradeOrderMapper;
    private final UserMapper userMapper;
    private final GoodsMapper goodsMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO createReview(Long currentUserId, CreateReviewRequest request) {
        // 1. 登录与入参基础校验
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (request == null || request.getOrderId() == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        if (request.getScore() == null || request.getScore() < 1 || request.getScore() > 5) {
            throw new BusinessException(400, "评分星级必须在1~5之间");
        }

        // 2. 订单存在性与状态校验
        TradeOrder order = tradeOrderMapper.selectById(request.getOrderId());
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getOrderStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(422, "订单尚未完成，暂无法评价");
        }

        LocalDateTime now = LocalDateTime.now();
        // 评价窗口期约束（天数取自 CreditRule，唯一真相源）
        if (CreditRule.isReviewWindowExpired(order.getCompletedTime(), now)) {
            throw new BusinessException(422,
                    "订单已完成超过" + CreditRule.REVIEW_WINDOW_DAYS + "天，评价通道已关闭");
        }

        // 3. 参与方校验 (买家或卖家)
        boolean isBuyer = Objects.equals(order.getBuyerId(), currentUserId);
        boolean isSeller = Objects.equals(order.getSellerId(), currentUserId);
        if (!isBuyer && !isSeller) {
            throw new AccessDeniedException("您不是该订单的参与方，无权评价");
        }

        // 4. 服务端安全推导被评价人 reviewedUserId，严禁客户端篡改
        Long reviewedUserId = isBuyer ? order.getSellerId() : order.getBuyerId();
        if (Objects.equals(currentUserId, reviewedUserId)) {
            throw new BusinessException(400, "禁止自买自评");
        }

        // 5. 重复评价校验 (业务前置校验，底层唯一索引兜底)
        Long existingCount = reviewMapper.selectCount(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getOrderId, order.getId())
                        .eq(Review::getReviewerId, currentUserId)
        );
        if (existingCount != null && existingCount > 0) {
            throw new BusinessException(409, "您已对该订单发表过评价，不可重复评价");
        }

        // 6. 内容规范化：只做"保真"处理，不做任何字符改写
        //    旧实现用黑名单清洗（删标签 + 删 "script"）会在入库阶段损坏正常文本
        //    （"javascript" → "java"、"<3 这本书" → " 这本书"），且黑名单天然可绕过。
        //    安全边界改由输出侧负责：正文按原文存储，任何 HTML 展示端必须经
        //    HtmlEscapeUtils.escape(...) 转义（本项目的 Flutter 客户端以纯文本渲染，不解析 HTML）。
        String content = normalizeContent(request.getContent());

        // 7. 标签规范化：同样保真，仅做去空白、去空项与数量/长度上限
        String tagsJoined = normalizeTags(request.getTags());

        // 8. 构建评价实体并落库
        Review review = Review.builder()
                .orderId(order.getId())
                .goodsId(order.getGoodsId())
                .reviewerId(currentUserId)
                .reviewedUserId(reviewedUserId)
                .score(request.getScore())
                .content(content)
                .tags(tagsJoined)
                .isAnonymous(Boolean.TRUE.equals(request.getIsAnonymous()))
                .status(ReviewStatus.VISIBLE)
                .likeCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();

        // 第 5 步的前置校验只能拦住"非并发"的重复评价；并发双击/重放下会双双通过前置校验，
        // 最终由 uk_review_order_reviewer 唯一索引兜底。此处把数据库唯一冲突翻译为 409 业务语义，
        // 而不是让它冒泡成 500（与 FavoriteServiceImpl 的既有写法对齐）。
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            log.warn("并发重复评价被唯一约束 uk_review_order_reviewer 拦截: orderId={}, reviewerId={}",
                    order.getId(), currentUserId);
            throw new BusinessException(409, "您已对该订单发表过评价，不可重复评价");
        }
        log.info("用户评价发布成功: reviewId={}, orderId={}, reviewerId={}, score={}",
                review.getId(), order.getId(), currentUserId, request.getScore());

        // 9. 触发领域事件联动信用计算
        eventPublisher.publishEvent(ReviewCreatedEvent.builder()
                .reviewId(review.getId())
                .orderId(review.getOrderId())
                .goodsId(review.getGoodsId())
                .reviewerId(review.getReviewerId())
                .reviewedUserId(review.getReviewedUserId())
                .score(review.getScore())
                .build());

        // 10. 组装 VO 返回
        User reviewer = userMapper.selectById(currentUserId);
        return convertToVOWithContext(review, reviewer, order.getGoodsTitleSnapshot(), currentUserId, false);
    }

    @Override
    public IPage<ReviewVO> getReviewsByUser(Long userId, Integer page, Integer size, Long currentUserId) {
        if (userId == null) {
            throw new BusinessException(400, "目标用户ID不能为空");
        }
        int pageNum = (page != null && page >= 1) ? page : 1;
        int pageSize = (size != null && size >= 1 && size <= 50) ? size : 10;

        Page<Review> pageParam = new Page<>(pageNum, pageSize);
        IPage<Review> reviewPage = reviewMapper.selectPage(
                pageParam,
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getReviewedUserId, userId)
                        .eq(Review::getStatus, ReviewStatus.VISIBLE)
                        .orderByDesc(Review::getCreatedTime)
        );

        return assembleReviewPage(reviewPage, currentUserId);
    }

    @Override
    public IPage<ReviewVO> getReviewsByGoods(Long goodsId, Integer page, Integer size, Long currentUserId) {
        if (goodsId == null) {
            throw new BusinessException(400, "商品ID不能为空");
        }
        int pageNum = (page != null && page >= 1) ? page : 1;
        int pageSize = (size != null && size >= 1 && size <= 50) ? size : 10;

        Page<Review> pageParam = new Page<>(pageNum, pageSize);
        IPage<Review> reviewPage = reviewMapper.selectPage(
                pageParam,
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getGoodsId, goodsId)
                        .eq(Review::getStatus, ReviewStatus.VISIBLE)
                        .orderByDesc(Review::getCreatedTime)
        );

        return assembleReviewPage(reviewPage, currentUserId);
    }

    @Override
    public OrderReviewStatusVO getReviewByOrder(Long orderId, Long currentUserId) {
        if (orderId == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        TradeOrder order = tradeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }

        boolean isBuyer = currentUserId != null && Objects.equals(order.getBuyerId(), currentUserId);
        boolean isSeller = currentUserId != null && Objects.equals(order.getSellerId(), currentUserId);
        if (!isBuyer && !isSeller) {
            throw new AccessDeniedException("您不是该订单的参与方，无权查看订单评价信息");
        }

        List<Review> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>().eq(Review::getOrderId, orderId)
        );

        Review myReviewEntity = null;
        Review peerReviewEntity = null;

        for (Review r : reviews) {
            if (currentUserId != null && Objects.equals(r.getReviewerId(), currentUserId)) {
                myReviewEntity = r;
            } else {
                peerReviewEntity = r;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        boolean canReview = false;
        String reason = null;

        if (order.getOrderStatus() != OrderStatus.COMPLETED) {
            reason = "订单尚未完成，暂无法评价";
        } else if (CreditRule.isReviewWindowExpired(order.getCompletedTime(), now)) {
            reason = "订单已完成超过" + CreditRule.REVIEW_WINDOW_DAYS + "天，评价通道已关闭";
        } else if (myReviewEntity != null) {
            reason = "您已经评价过该订单";
        } else {
            canReview = true;
        }

        ReviewVO myReviewVO = null;
        if (myReviewEntity != null) {
            User reviewer = userMapper.selectById(myReviewEntity.getReviewerId());
            boolean isLiked = isUserLiked(currentUserId, myReviewEntity.getId());
            myReviewVO = convertToVOWithContext(myReviewEntity, reviewer, order.getGoodsTitleSnapshot(), currentUserId, isLiked);
        }

        ReviewVO peerReviewVO = null;
        if (peerReviewEntity != null) {
            User reviewer = userMapper.selectById(peerReviewEntity.getReviewerId());
            boolean isLiked = isUserLiked(currentUserId, peerReviewEntity.getId());
            peerReviewVO = convertToVOWithContext(peerReviewEntity, reviewer, order.getGoodsTitleSnapshot(), currentUserId, isLiked);
        }

        return OrderReviewStatusVO.builder()
                .orderId(orderId)
                .isBuyer(isBuyer)
                .canReview(canReview)
                .reasonIfNotEligible(reason)
                .myReview(myReviewVO)
                .peerReview(peerReviewVO)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewLikeVO likeReview(Long currentUserId, Long reviewId) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (reviewId == null) {
            throw new BusinessException(400, "评价ID不能为空");
        }

        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "评价不存在");
        }
        if (review.getStatus() == ReviewStatus.AUDIT_REJECTED) {
            throw new BusinessException(422, "该评价已被平台屏蔽，无法进行互动操作");
        }

        // 禁止自点赞
        if (Objects.equals(review.getReviewerId(), currentUserId)) {
            throw new BusinessException(400, "禁止对自己发表的评价点赞");
        }

        // 前置校验防重
        Long count = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, reviewId)
                        .eq(ReviewLike::getUserId, currentUserId)
        );
        if (count != null && count > 0) {
            throw new BusinessException(409, "您已经赞过该评价，不可重复点赞");
        }

        // 尝试插入 review_like，唯一约束 uk_review_like_review_user 兜底高并发竞争
        try {
            ReviewLike like = ReviewLike.builder()
                    .reviewId(reviewId)
                    .userId(currentUserId)
                    .createdTime(LocalDateTime.now())
                    .build();
            reviewLikeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            log.warn("并发点赞触发唯一约束拦截: reviewId={}, userId={}", reviewId, currentUserId);
            throw new BusinessException(409, "您已经赞过该评价，不可重复点赞");
        }

        // 原子累加 like_count
        reviewMapper.incrementLikeCount(reviewId);

        Review updatedReview = reviewMapper.selectById(reviewId);
        int currentLikes = (updatedReview != null && updatedReview.getLikeCount() != null) ? updatedReview.getLikeCount() : 1;

        return ReviewLikeVO.builder()
                .reviewId(reviewId)
                .liked(true)
                .likeCount(currentLikes)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewLikeVO unlikeReview(Long currentUserId, Long reviewId) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (reviewId == null) {
            throw new BusinessException(400, "评价ID不能为空");
        }

        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "评价不存在");
        }

        // 仅在实际删除了记录时，才原子扣减 like_count，防止重复扣减导致负数
        int affectedRows = reviewLikeMapper.delete(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, reviewId)
                        .eq(ReviewLike::getUserId, currentUserId)
        );

        if (affectedRows > 0) {
            reviewMapper.decrementLikeCount(reviewId);
        }

        Review updatedReview = reviewMapper.selectById(reviewId);
        int currentLikes = (updatedReview != null && updatedReview.getLikeCount() != null) ? updatedReview.getLikeCount() : 0;

        return ReviewLikeVO.builder()
                .reviewId(reviewId)
                .liked(false)
                .likeCount(currentLikes)
                .build();
    }

    @Override
    public ReviewLikeVO getLikeStatus(Long currentUserId, Long reviewId) {
        if (reviewId == null) {
            throw new BusinessException(400, "评价ID不能为空");
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(404, "评价不存在");
        }
        if (review.getStatus() == ReviewStatus.AUDIT_REJECTED) {
            throw new BusinessException(422, "该评价已被平台屏蔽，无法查看互动数据");
        }

        boolean liked = isUserLiked(currentUserId, reviewId);
        int likeCount = review.getLikeCount() != null ? review.getLikeCount() : 0;

        return ReviewLikeVO.builder()
                .reviewId(reviewId)
                .liked(liked)
                .likeCount(likeCount)
                .build();
    }

    /**
     * 基于 4 阶段批量内存聚合算法彻底根除 N+1 查询
     */
    private IPage<ReviewVO> assembleReviewPage(IPage<Review> reviewPage, Long currentUserId) {
        List<Review> records = reviewPage.getRecords();
        if (records == null || records.isEmpty()) {
            Page<ReviewVO> emptyPage = new Page<>(reviewPage.getCurrent(), reviewPage.getSize(), reviewPage.getTotal());
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }

        // 1. 批量查询评价人
        Set<Long> reviewerIds = records.stream()
                .map(Review::getReviewerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = reviewerIds.isEmpty() ? Collections.emptyMap() :
                userMapper.selectBatchIds(reviewerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (k1, k2) -> k1));

        // 2. 批量查询商品信息
        Set<Long> goodsIds = records.stream()
                .map(Review::getGoodsId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Goods> goodsMap = goodsIds.isEmpty() ? Collections.emptyMap() :
                goodsMapper.selectBatchIds(goodsIds).stream()
                        .collect(Collectors.toMap(Goods::getId, g -> g, (k1, k2) -> k1));

        // 3. 批量查询当前登录用户的点赞状态 (仅限已登录)
        Set<Long> likedReviewIds = new HashSet<>();
        if (currentUserId != null) {
            List<Long> reviewIds = records.stream().map(Review::getId).collect(Collectors.toList());
            if (!reviewIds.isEmpty()) {
                List<ReviewLike> likes = reviewLikeMapper.selectList(
                        new LambdaQueryWrapper<ReviewLike>()
                                .eq(ReviewLike::getUserId, currentUserId)
                                .in(ReviewLike::getReviewId, reviewIds)
                );
                likedReviewIds = likes.stream()
                        .map(ReviewLike::getReviewId)
                        .collect(Collectors.toSet());
            }
        }

        // 4. 内存批量拼装 VO (O(1) 访问)
        final Set<Long> finalLikedReviewIds = likedReviewIds;
        List<ReviewVO> voList = records.stream().map(review -> {
            User reviewer = userMap.get(review.getReviewerId());
            Goods goods = goodsMap.get(review.getGoodsId());
            boolean liked = finalLikedReviewIds.contains(review.getId());
            return convertToVOWithContext(review, reviewer, goods != null ? goods.getTitle() : null, currentUserId, liked);
        }).collect(Collectors.toList());

        Page<ReviewVO> voPage = new Page<>(reviewPage.getCurrent(), reviewPage.getSize(), reviewPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    private ReviewVO convertToVOWithContext(
            Review review,
            User reviewer,
            String goodsTitle,
            Long viewingUserId,
            Boolean isLiked
    ) {
        if (review == null) {
            return null;
        }

        String reviewerNickname = "校友";
        String reviewerAvatar = null;

        boolean isAnonymous = Boolean.TRUE.equals(review.getIsAnonymous());
        boolean isSelf = viewingUserId != null && Objects.equals(viewingUserId, review.getReviewerId());

        if (reviewer != null) {
            if (isAnonymous && !isSelf) {
                reviewerNickname = "校友***";
                reviewerAvatar = null;
            } else {
                reviewerNickname = reviewer.getNickname() != null && !reviewer.getNickname().isEmpty()
                        ? reviewer.getNickname()
                        : reviewer.getUsername();
                reviewerAvatar = reviewer.getAvatar();
            }
        }

        List<String> tagsList = Collections.emptyList();
        if (review.getTags() != null && !review.getTags().trim().isEmpty()) {
            tagsList = Arrays.stream(review.getTags().split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.toList());
        }

        return ReviewVO.builder()
                .id(review.getId())
                .orderId(review.getOrderId())
                .goodsId(review.getGoodsId())
                .goodsTitle(goodsTitle)
                .reviewerId(isAnonymous && !isSelf ? null : review.getReviewerId())
                .reviewerNickname(reviewerNickname)
                .reviewerAvatar(reviewerAvatar)
                .reviewedUserId(review.getReviewedUserId())
                .score(review.getScore())
                .content(review.getContent())
                .tags(tagsList)
                .isAnonymous(isAnonymous)
                .status(review.getStatus() != null ? review.getStatus().name() : "VISIBLE")
                .likeCount(review.getLikeCount() != null ? review.getLikeCount() : 0)
                .likedByCurrentUser(isLiked != null ? isLiked : false)
                .createdTime(review.getCreatedTime())
                .build();
    }

    private boolean isUserLiked(Long userId, Long reviewId) {
        if (userId == null || reviewId == null) {
            return false;
        }
        Long count = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, reviewId)
                        .eq(ReviewLike::getUserId, userId)
        );
        return count != null && count > 0;
    }

    /** 评价正文最大长度（超出部分截断，与既有行为一致）。 */
    private static final int REVIEW_CONTENT_MAX_LENGTH = 500;

    /** 单个标签最大长度（超出部分截断）。 */
    private static final int REVIEW_TAG_MAX_LENGTH = 30;

    /** 最多保留的标签数量（与既有行为一致）。 */
    private static final int REVIEW_TAG_MAX_COUNT = 5;

    /**
     * 评价正文规范化：<b>只做长度上限与首尾空白处理，不改写任何字符</b>。
     *
     * <p>存储原文是刻意的选择：黑名单式"清洗"会把正常文本改坏（不可逆）且覆盖不全，
     * 真正的防护点是输出侧转义（见 {@link HtmlEscapeUtils}）。</p>
     *
     * <p>这里只对"包含 HTML 标记迹象"打一条观测日志：既不改写内容，也不静默忽略，
     * 让运营/风控能观察到可疑内容（例如有人试图在评价里塞标签）。</p>
     */
    private String normalizeContent(String rawContent) {
        if (rawContent == null) {
            return null;
        }
        String content = rawContent.trim();
        if (HtmlEscapeUtils.containsHtmlMarkup(content)) {
            log.warn("[CONTENT-HTML-MARKUP] 评价正文包含 HTML 标记，已按原文存储（展示端必须转义）: length={}",
                    content.length());
        }
        if (content.length() > REVIEW_CONTENT_MAX_LENGTH) {
            content = content.substring(0, REVIEW_CONTENT_MAX_LENGTH);
        }
        return content;
    }

    /**
     * 标签规范化：去首尾空白、丢弃空项、限制单标签长度与总数量，<b>不改写字符内容</b>。
     */
    private String normalizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return null;
        }
        String joined = rawTags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> tag.length() > REVIEW_TAG_MAX_LENGTH
                        ? tag.substring(0, REVIEW_TAG_MAX_LENGTH) : tag)
                .limit(REVIEW_TAG_MAX_COUNT)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }
}
