package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.*;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.event.ReviewCreatedEvent;
import com.campustrade.exception.BusinessException;
import com.campustrade.listener.CreditReviewEventListener;
import com.campustrade.mapper.*;
import com.campustrade.service.CreditService;
import com.campustrade.service.ReviewService;
import com.campustrade.vo.review.OrderReviewStatusVO;
import com.campustrade.vo.review.ReviewVO;
import com.campustrade.enums.GoodsStatus;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 5-D: 评价系统后端开发与信用联动自动化测试套件
 * 严格覆盖要求的 12 项测试场景
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage5DTests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private CreditReviewEventListener creditReviewEventListener;

    @Autowired
    private com.campustrade.service.OrderService orderService;

    private Long buyerId;
    private Long sellerId;
    private Long thirdPartyId;
    private Long goodsId;

    @BeforeEach
    void setUp() {
        buyerId = 77770001L;
        sellerId = 77770002L;
        thirdPartyId = 77770003L;
        goodsId = 77771001L;

        initTestUser(buyerId, "stage5d_buyer", "买家同学");
        initTestUser(sellerId, "stage5d_seller", "卖家同学");
        initTestUser(thirdPartyId, "stage5d_third", "路人同学");

        initTestGoods(goodsId, sellerId, "二手iPad测试商品");
    }

    private void initTestUser(Long id, String username, String nickname) {
        User u = userMapper.selectById(id);
        if (u == null) {
            u = User.builder()
                    .id(id)
                    .username(username)
                    .password("encoded_pwd")
                    .nickname(nickname)
                    .avatar("https://avatar.test/" + username + ".png")
                    .role("USER")
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            userMapper.insert(u);
        }
    }

    private void initTestGoods(Long gId, Long sId, String title) {
        Goods g = goodsMapper.selectById(gId);
        if (g == null) {
            g = Goods.builder()
                    .id(gId)
                    .sellerId(sId)
                    .schoolId(1L)
                    .categoryId(1L)
                    .title(title)
                    .description("测试商品描述")
                    .price(new BigDecimal("199.00"))
                    .conditionLevel("95新")
                    .status(GoodsStatus.SOLD.getCode())
                    .viewCount(0)
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            goodsMapper.insert(g);
        }
    }

    private TradeOrder createTestOrder(OrderStatus status, Long bId, Long sId, Long gId) {
        Long targetGoodsId = gId;
        if (targetGoodsId == null || status == OrderStatus.WAIT_MEET || status == OrderStatus.WAIT_SELLER_CONFIRM) {
            targetGoodsId = Math.abs(UUID.randomUUID().getMostSignificantBits());
            initTestGoods(targetGoodsId, sId, "测试商品_" + targetGoodsId);
        }
        TradeOrder order = TradeOrder.builder()
                .orderNo("ORD_5D_" + UUID.randomUUID().toString().substring(0, 8))
                .buyerId(bId)
                .sellerId(sId)
                .goodsId(targetGoodsId)
                .goodsTitleSnapshot("测试二手iPad")
                .goodsPriceSnapshot(new BigDecimal("199.00"))
                .goodsImageSnapshot("https://image.test/ipad.jpg")
                .meetLocation("图书馆正门")
                .buyerMessage("请准时")
                .orderStatus(status)
                .completedTime(status == OrderStatus.COMPLETED ? LocalDateTime.now() : null)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        tradeOrderMapper.insert(order);
        return order;
    }

    @Test
    @Order(1)
    @DisplayName("测试1: Flyway V6 数据库迁移成功执行且为当前版本")
    void test01_flyway_v6_applied_successfully() {
        assertNotNull(flyway);
        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 6, "至少已应用 6 个 Flyway migrations");

        boolean v6Found = Arrays.stream(applied).anyMatch(m ->
                "6".equals(m.getVersion().getVersion()) && m.getState() == MigrationState.SUCCESS
        );
        assertTrue(v6Found, "Flyway 迁移版本 V6 必须成功应用且状态为 SUCCESS");

        MigrationInfo v6 = Arrays.stream(applied)
                .filter(m -> "6".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v6, "V6 迁移必须存在");
        assertEquals("6", v6.getVersion().getVersion(), "V6 版本号必须为 6");
        assertEquals("create review domain", v6.getDescription());

        // 验证表和唯一索引
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'review'",
                Integer.class
        );
        assertEquals(1, tableCount, "campus_trade.review 表必须存在");

        Integer ukIndexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'review' AND indexname = 'uk_review_order_reviewer'",
                Integer.class
        );
        assertEquals(1, ukIndexCount, "uk_review_order_reviewer 唯一索引必须存在");
    }

    @Test
    @Order(2)
    @DisplayName("测试2: COMPLETED 订单可以正常创建评价")
    void test02_completed_order_can_be_reviewed() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("物美价廉，学长很守时！")
                .tags(List.of("守时诚信", "成色极佳"))
                .anonymous(false)
                .build();

        ReviewVO vo = reviewService.createReview(buyerId, req);
        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals(order.getId(), vo.getOrderId());
        assertEquals(5, vo.getScore());
        assertEquals("买家同学", vo.getReviewerNickname());
        assertEquals(sellerId, vo.getReviewedUserId());
        assertFalse(vo.getIsAnonymous());

        Review entity = reviewMapper.selectById(vo.getId());
        assertNotNull(entity);
        assertEquals(ReviewStatus.VISIBLE, entity.getStatus());
    }

    @Test
    @Order(3)
    @DisplayName("测试3: 未完成订单不能评价 (抛出 422)")
    void test03_uncompleted_order_cannot_be_reviewed() {
        TradeOrder waitMeetOrder = createTestOrder(OrderStatus.WAIT_MEET, buyerId, sellerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(waitMeetOrder.getId())
                .score(5)
                .content("还没面交就想评价")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.createReview(buyerId, req)
        );
        assertEquals(422, ex.getCode(), "未完成订单评价必须返回 422 状态码");
    }

    @Test
    @Order(4)
    @DisplayName("测试4: 第三方非参与者用户不能评价 (抛出 403 AccessDeniedException)")
    void test04_third_party_user_cannot_review() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("我是路人我来评价")
                .build();

        assertThrows(AccessDeniedException.class, () ->
                reviewService.createReview(thirdPartyId, req)
        );
    }

    @Test
    @Order(5)
    @DisplayName("测试5: 不能评价自己 (自买自评防御拦截)")
    void test05_cannot_review_oneself() {
        // 创建一个 buyerId == sellerId 的订单模拟防刷校验
        TradeOrder selfOrder = createTestOrder(OrderStatus.COMPLETED, buyerId, buyerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(selfOrder.getId())
                .score(5)
                .content("自己给自己好评")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.createReview(buyerId, req)
        );
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("自买自评") || ex.getMessage().contains("自己"));
    }

    @Test
    @Order(6)
    @DisplayName("测试6: 同一订单重复评价被拦截 (返回 409)")
    void test06_duplicate_review_is_intercepted() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("第一次评价")
                .build();

        reviewService.createReview(buyerId, req);

        // 第二次提交评价
        CreateReviewRequest req2 = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(4)
                .content("第二次重复评价")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.createReview(buyerId, req2)
        );
        assertEquals(409, ex.getCode(), "重复评价必须返回 409 冲突状态码");
    }

    @Test
    @Order(7)
    @DisplayName("测试7: 评分范围校验 (非 1~5 星返回 400)")
    void test07_score_range_validation() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        // 0 星
        CreateReviewRequest req0 = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(0)
                .content("0星评分")
                .build();
        BusinessException ex0 = assertThrows(BusinessException.class, () -> reviewService.createReview(buyerId, req0));
        assertEquals(400, ex0.getCode());

        // 6 星
        CreateReviewRequest req6 = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(6)
                .content("6星评分")
                .build();
        BusinessException ex6 = assertThrows(BusinessException.class, () -> reviewService.createReview(buyerId, req6));
        assertEquals(400, ex6.getCode());
    }

    @Test
    @Order(8)
    @DisplayName("测试8: 5星好评触发信用联动，被评价人信用 +3")
    void test08_five_star_review_adds_three_credit_score() {
        Long targetSeller = 77770010L;
        initTestUser(targetSeller, "seller_five_star", "五星卖家");

        // 初始化信用档案为 100 分
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, targetSeller));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller));
        reviewMapper.delete(new LambdaQueryWrapper<Review>().eq(Review::getReviewedUserId, targetSeller));
        creditService.getOrCreateCredit(targetSeller);

        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, targetSeller, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("非常棒，强烈推荐5星好评！")
                .build();

        ReviewVO vo = reviewService.createReview(buyerId, req);
        assertNotNull(vo);

        // 验证卖家信用增加 3 分 (100 -> 103)
        UserCredit sellerCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller)
        );
        assertEquals(103, sellerCredit.getCreditScore(), "5星好评后信用分必须增加 3 分");
        assertEquals(1, sellerCredit.getGoodReviewCount(), "好评数必须 +1");

        // 验证流水日志
        UserCreditLog creditLog = userCreditLogMapper.selectOne(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, targetSeller)
                        .eq(UserCreditLog::getRelatedType, "REVIEW")
                        .eq(UserCreditLog::getRelatedId, vo.getId())
        );
        assertNotNull(creditLog);
        assertEquals(3, creditLog.getChangeScore());
        assertEquals("REVIEW_GOOD", creditLog.getChangeType());
    }

    @Test
    @Order(9)
    @DisplayName("测试9: 1星差评触发信用联动，被评价人信用 -5，等级下调")
    void test09_one_star_review_deducts_five_credit_score() {
        Long targetSeller = 77770011L;
        initTestUser(targetSeller, "seller_one_star", "差评卖家");

        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, targetSeller));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller));
        creditService.getOrCreateCredit(targetSeller);

        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, targetSeller, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(1)
                .content("严重虚假描述，态度极其恶劣！")
                .build();

        ReviewVO vo = reviewService.createReview(buyerId, req);
        assertNotNull(vo);

        // 验证卖家信用扣除 5 分 (100 -> 95)，等级调整为 FAIR
        UserCredit sellerCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller)
        );
        assertEquals(95, sellerCredit.getCreditScore(), "1星差评后信用分必须扣除 5 分");
        assertEquals("FAIR", sellerCredit.getCreditLevel(), "95分对应等级必须自动调整为 FAIR");
        assertEquals(1, sellerCredit.getBadReviewCount(), "差评数必须 +1");

        UserCreditLog creditLog = userCreditLogMapper.selectOne(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, targetSeller)
                        .eq(UserCreditLog::getRelatedType, "REVIEW")
                        .eq(UserCreditLog::getRelatedId, vo.getId())
        );
        assertNotNull(creditLog);
        assertEquals(-5, creditLog.getChangeScore());
        assertEquals("REVIEW_BAD", creditLog.getChangeType());
    }

    @Test
    @Order(10)
    @DisplayName("测试10: 重复领域事件不会重复增加或扣减信用 (强幂等)")
    void test10_duplicate_event_does_not_repeat_credit() {
        Long targetSeller = 77770010L; // 使用 test08 的卖家 (当前 103 分)

        UserCredit beforeCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller)
        );
        int scoreBefore = beforeCredit.getCreditScore();

        // 找到该 seller 的一条已有 review
        Review review = reviewMapper.selectOne(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getReviewedUserId, targetSeller)
                        .orderByDesc(Review::getId)
                        .last("LIMIT 1")
        );
        assertNotNull(review);

        // 重复触发一次相同的 ReviewCreatedEvent
        ReviewCreatedEvent duplicateEvent = ReviewCreatedEvent.builder()
                .reviewId(review.getId())
                .orderId(review.getOrderId())
                .goodsId(review.getGoodsId())
                .reviewerId(review.getReviewerId())
                .reviewedUserId(targetSeller)
                .score(5)
                .build();

        creditReviewEventListener.handleReviewCreated(duplicateEvent);

        UserCredit afterCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, targetSeller)
        );
        assertEquals(scoreBefore, afterCredit.getCreditScore(), "重复事件驱动下信用分不能重复累加");
    }

    @Test
    @Order(11)
    @DisplayName("测试11: 评价列表与订单双向评价状态查询正常")
    void test11_review_query_by_user_goods_and_order() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        // 买家评价卖家
        CreateReviewRequest reqBuyer = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("买家给的好评")
                .build();
        reviewService.createReview(buyerId, reqBuyer);

        // 卖家评价买家
        CreateReviewRequest reqSeller = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(4)
                .content("卖家给的好评")
                .build();
        reviewService.createReview(sellerId, reqSeller);

        // 1. 查询卖家收到的评价
        IPage<ReviewVO> userReviews = reviewService.getReviewsByUser(sellerId, 1, 10, null);
        assertTrue(userReviews.getTotal() >= 1);

        // 2. 查询商品收到的评价
        IPage<ReviewVO> goodsReviews = reviewService.getReviewsByGoods(goodsId, 1, 10, null);
        assertTrue(goodsReviews.getTotal() >= 1);

        // 3. 买家视角查询订单双向状态
        OrderReviewStatusVO statusVO = reviewService.getReviewByOrder(order.getId(), buyerId);
        assertNotNull(statusVO);
        assertTrue(statusVO.getIsBuyer());
        assertFalse(statusVO.getCanReview(), "买家已评过，canReview 应为 false");
        assertNotNull(statusVO.getMyReview());
        assertEquals("买家给的好评", statusVO.getMyReview().getContent());
        assertNotNull(statusVO.getPeerReview());
        assertEquals("卖家给的好评", statusVO.getPeerReview().getContent());
    }

    @Test
    @Order(12)
    @DisplayName("测试12: 匿名评价展示脱敏验证 (非本人脱去昵称与头像)")
    void test12_anonymous_review_masked() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("这是一条匿名评价内容")
                .anonymous(true)
                .build();

        ReviewVO myVO = reviewService.createReview(buyerId, req);
        // 本人查看可见
        assertTrue(myVO.getIsAnonymous());

        // 第三方或被评价人查询该评价
        IPage<ReviewVO> pageResult = reviewService.getReviewsByUser(sellerId, 1, 10, null);
        ReviewVO maskedVO = pageResult.getRecords().stream()
                .filter(r -> r.getId().equals(myVO.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(maskedVO);
        assertTrue(maskedVO.getIsAnonymous());
        assertEquals("校友***", maskedVO.getReviewerNickname(), "匿名评价昵称必须脱敏为 校友***");
        assertNull(maskedVO.getReviewerAvatar(), "匿名评价头像必须脱敏为 null");
        assertNull(maskedVO.getReviewerId(), "匿名评价 reviewerId 对外必须脱敏为 null");
    }

    @Test
    @Order(13)
    @DisplayName("测试13: 7天评价窗口约束（订单完成超过168小时无法评价）")
    void test13_review_expired_after_7_days() {
        TradeOrder expiredOrder = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);
        // 设置完成时间为 8 天前 (192小时前)
        expiredOrder.setCompletedTime(LocalDateTime.now().minusHours(192));
        tradeOrderMapper.updateById(expiredOrder);

        CreateReviewRequest req = CreateReviewRequest.builder()
                .orderId(expiredOrder.getId())
                .score(5)
                .content("8天前的订单尝试评价")
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.createReview(buyerId, req)
        );
        assertEquals(422, ex.getCode(), "逾期评价必须返回 422 状态码");
        assertTrue(ex.getMessage().contains("7天") || ex.getMessage().contains("评价通道已关闭"));

        // 查询双向评价状态也应感知已逾期
        OrderReviewStatusVO statusVO = reviewService.getReviewByOrder(expiredOrder.getId(), buyerId);
        assertFalse(statusVO.getCanReview(), "超过7天后 canReview 必须为 false");
        assertTrue(statusVO.getReasonIfNotEligible().contains("7天") || statusVO.getReasonIfNotEligible().contains("评价通道已关闭"));
    }

    @Test
    @Order(14)
    @DisplayName("测试14: 订单顺利完成触发双方信用联动（+2分，completed_count+1，trade_count+1，流水沉淀）")
    void test14_order_completed_credit_linkage() {
        Long testBuyer = 77770020L;
        Long testSeller = 77770021L;
        initTestUser(testBuyer, "order_buyer_20", "买家20");
        initTestUser(testSeller, "order_seller_21", "卖家21");

        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().in(UserCreditLog::getUserId, List.of(testBuyer, testSeller)));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().in(UserCredit::getUserId, List.of(testBuyer, testSeller)));
        creditService.getOrCreateCredit(testBuyer);
        creditService.getOrCreateCredit(testSeller);

        Long testGId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        initTestGoods(testGId, testSeller, "待面交测试商品");
        Goods g = goodsMapper.selectById(testGId);
        g.setStatus(GoodsStatus.ON_SALE.getCode());
        goodsMapper.updateById(g);

        TradeOrder order = orderService.createOrder(testBuyer, testGId, "操场", "留言");
        orderService.confirmOrder(order.getId(), testSeller);
        orderService.completeOrder(order.getId(), testBuyer);

        // 验证买卖双方信用积分 + 2，completed_count = 1，trade_count = 1
        UserCredit buyerCredit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testBuyer));
        assertEquals(102, buyerCredit.getCreditScore());
        assertEquals(1L, buyerCredit.getCompletedCount());
        assertEquals(1, buyerCredit.getTradeCount());

        UserCredit sellerCredit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testSeller));
        assertEquals(102, sellerCredit.getCreditScore());
        assertEquals(1L, sellerCredit.getCompletedCount());
        assertEquals(1, sellerCredit.getTradeCount());

        // 验证流水生成
        Long logCount = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLog>()
                .eq(UserCreditLog::getRelatedType, "ORDER")
                .eq(UserCreditLog::getRelatedId, order.getId())
                .eq(UserCreditLog::getChangeType, "TRADE_COMPLETED"));
        assertEquals(2, logCount, "双方必须各产生一条 TRADE_COMPLETED 审计流水");
    }

    @Test
    @Order(15)
    @DisplayName("测试15: 待面交阶段取消订单扣除违约方1分，cancel_count+1；确认前取消0分变动")
    void test15_order_cancelled_credit_penalty() {
        Long testBuyer = 77770022L;
        Long testSeller = 77770023L;
        initTestUser(testBuyer, "cancel_buyer_22", "买家22");
        initTestUser(testSeller, "cancel_seller_23", "卖家23");

        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().in(UserCreditLog::getUserId, List.of(testBuyer, testSeller)));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().in(UserCredit::getUserId, List.of(testBuyer, testSeller)));
        creditService.getOrCreateCredit(testBuyer);
        creditService.getOrCreateCredit(testSeller);

        // 场景 A: 卖家确认前取消 -> 0 分变动
        Long gIdA = Math.abs(UUID.randomUUID().getMostSignificantBits());
        initTestGoods(gIdA, testSeller, "确认前取消商品");
        Goods gA = goodsMapper.selectById(gIdA);
        gA.setStatus(GoodsStatus.ON_SALE.getCode());
        goodsMapper.updateById(gA);

        TradeOrder orderA = orderService.createOrder(testBuyer, gIdA, "二餐", "买");
        orderService.cancelOrder(orderA.getId(), testBuyer, "未确认前临时改变主意");

        UserCredit buyerCreditA = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testBuyer));
        assertEquals(100, buyerCreditA.getCreditScore(), "卖家接单前取消，积分不扣减");
        assertEquals(0L, buyerCreditA.getCancelCount());

        // 场景 B: 卖家接单后(WAIT_MEET)违约取消 -> 发起方扣 1 分，cancel_count + 1
        Long gIdB = Math.abs(UUID.randomUUID().getMostSignificantBits());
        initTestGoods(gIdB, testSeller, "待面交取消商品");
        Goods gB = goodsMapper.selectById(gIdB);
        gB.setStatus(GoodsStatus.ON_SALE.getCode());
        goodsMapper.updateById(gB);

        TradeOrder orderB = orderService.createOrder(testBuyer, gIdB, "图书馆", "买");
        orderService.confirmOrder(orderB.getId(), testSeller);
        orderService.cancelOrder(orderB.getId(), testBuyer, "待面交阶段违约放鸽子");

        UserCredit buyerCreditB = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testBuyer));
        assertEquals(99, buyerCreditB.getCreditScore(), "待面交阶段取消，违约方必须扣除 1 分");
        assertEquals(1L, buyerCreditB.getCancelCount(), "违约方 cancel_count 必须 + 1");
    }

    @Test
    @Order(16)
    @DisplayName("测试16: 第三方非订单参与者无权查询订单评价状态 (抛出 403 AccessDeniedException)")
    void test16_third_party_cannot_access_order_review_status() {
        TradeOrder order = createTestOrder(OrderStatus.COMPLETED, buyerId, sellerId, goodsId);

        assertThrows(AccessDeniedException.class, () ->
                reviewService.getReviewByOrder(order.getId(), thirdPartyId)
        );
    }
}
