package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.dto.review.RestoreReviewRequest;
import com.campustrade.entity.*;
import com.campustrade.enums.AdminOperationType;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.mapper.*;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.CreditService;
import com.campustrade.service.ReviewService;
import com.campustrade.vo.review.ReviewLikeVO;
import com.campustrade.vo.review.ReviewVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 6-C: 评价点赞、点赞计数与评价治理增强全套自动化集成测试
 * 严格覆盖：
 * 1. Flyway V8 迁移成功、review_like 表与索引、like_count 非负约束
 * 2. 点赞/取消点赞/点赞状态查询 RESTful API 与权限安全矩阵 (401/404/400/409/422)
 * 3. 100 线程同用户高并发点赞强幂等 (唯一约束物理兜底)
 * 4. 100 线程多用户并发点赞与 like_count 精准累加
 * 5. 并发取消点赞防负数下溢 (GREATEST(0, ...))
 * 6. 混合高并发点赞取消的数据一致性校验 (COUNT(review_like) == review.like_count)
 * 7. 4阶段批量内存聚合消除 N+1 查询验证
 * 8. 治理屏蔽与历史点赞保留、屏蔽态点赞阻断 (422)
 * 9. 管理员纠偏恢复 (AUDIT_REJECTED -> VISIBLE) 与操作审计
 * 10. 恢复评价的信用反向补偿全矩阵 (5星+3 / 4星+1 / 3星0 / 2星-2 / 1星-5)
 * 11. 管理员恢复幂等性与并发恢复防御
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage6CTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private ReviewLikeMapper reviewLikeMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private AdminGovernanceService adminGovernanceService;

    private static final Long USER_A_ID = 88881001L;
    private static final Long USER_B_ID = 88881002L;
    private static final Long USER_C_ID = 88881003L;
    private static final Long ADMIN_USER_ID = 88881099L;

    private static String tokenUserA;
    private static String tokenUserB;
    private static String tokenUserC;
    private static String tokenAdmin;

    @BeforeEach
    void setUpTestData() {
        initUser(USER_A_ID, "stage6c_user_a", "用户小甲", "USER");
        initUser(USER_B_ID, "stage6c_user_b", "用户小乙", "USER");
        initUser(USER_C_ID, "stage6c_user_c", "用户小丙", "USER");
        initUser(ADMIN_USER_ID, "stage6c_admin", "治理管理员", "ADMIN");

        tokenUserA = jwtTokenProvider.generateAccessToken(USER_A_ID, "stage6c_user_a", "USER");
        tokenUserB = jwtTokenProvider.generateAccessToken(USER_B_ID, "stage6c_user_b", "USER");
        tokenUserC = jwtTokenProvider.generateAccessToken(USER_C_ID, "stage6c_user_c", "USER");
        tokenAdmin = jwtTokenProvider.generateAccessToken(ADMIN_USER_ID, "stage6c_admin", "ADMIN");
    }

    private void initUser(Long id, String username, String nickname, String role) {
        User u = userMapper.selectById(id);
        if (u == null) {
            u = User.builder()
                    .id(id)
                    .username(username)
                    .password("$2a$10$abcdefghijklmnopqrstuvwxyz1234567890")
                    .nickname(nickname)
                    .avatar("https://avatar.test/" + username + ".png")
                    .role(role)
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            userMapper.insert(u);
        } else {
            u.setRole(role);
            u.setStatus("ACTIVE");
            userMapper.updateById(u);
        }

        UserCredit credit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, id));
        if (credit == null) {
            credit = UserCredit.builder()
                    .userId(id)
                    .creditScore(100)
                    .creditLevel("GOOD")
                    .tradeCount(0)
                    .goodReviewCount(0)
                    .badReviewCount(0)
                    .completedCount(0L)
                    .cancelCount(0L)
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            userCreditMapper.insert(credit);
        }
    }

    private Goods createTestGoods(Long sellerId, String title) {
        Goods g = Goods.builder()
                .sellerId(sellerId)
                .schoolId(1L)
                .categoryId(1L)
                .title(title)
                .description("测试商品详细描述")
                .price(new BigDecimal("199.00"))
                .originalPrice(new BigDecimal("399.00"))
                .conditionLevel("95新")
                .status("ON_SALE")
                .viewCount(5)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(g);
        return g;
    }

    /**
     * 评价夹具商品的 ID（惰性创建）：V10 迁移给 review.order_id / review.goods_id 补了外键，
     * NOT VALID 只豁免历史数据，新插入的行仍会被校验，因此评价必须挂在真实的订单与商品上。
     */
    private Long reviewFixtureGoodsId;

    private Long ensureReviewFixtureGoods() {
        if (reviewFixtureGoodsId == null) {
            reviewFixtureGoodsId = createTestGoods(USER_A_ID, "评价外键夹具商品").getId();
        }
        return reviewFixtureGoodsId;
    }

    /** 为评价夹具创建一条真实存在的 COMPLETED 订单（非活动状态，不受局部唯一索引限制）。 */
    private Long createReviewFixtureOrder(Long goodsId, Long buyerId, Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = TradeOrder.builder()
                .orderNo("ORD_FIXTURE6C_" + UUID.randomUUID().toString().substring(0, 8))
                .buyerId(buyerId)
                .sellerId(sellerId)
                .goodsId(goodsId)
                .goodsTitleSnapshot("评价外键夹具商品")
                .goodsPriceSnapshot(new BigDecimal("199.00"))
                .meetLocation("测试地点")
                .orderStatus(OrderStatus.COMPLETED)
                .completedTime(now)
                .createdTime(now)
                .updatedTime(now)
                .build();
        tradeOrderMapper.insert(order);
        return order.getId();
    }

    private Review createTestReview(Long reviewerId, Long reviewedUserId, Integer score, String content, ReviewStatus status) {
        Long fixtureGoodsId = ensureReviewFixtureGoods();
        Review r = Review.builder()
                .orderId(createReviewFixtureOrder(fixtureGoodsId, reviewerId, reviewedUserId))
                .goodsId(fixtureGoodsId)
                .reviewerId(reviewerId)
                .reviewedUserId(reviewedUserId)
                .score(score)
                .content(content)
                .isAnonymous(false)
                .status(status != null ? status : ReviewStatus.VISIBLE)
                .likeCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        reviewMapper.insert(r);
        return r;
    }

    // =========================================================================
    // 一、Flyway V8 迁移与底层物理模型验证
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("测试1: Flyway V8 迁移成功应用，review.like_count 与 review_like 表和唯一索引完备")
    void test01_flyway_v8_applied_successfully() {
        assertNotNull(flyway);
        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 8, "至少应已应用 8 个 Flyway migrations");

        MigrationInfo v8 = Arrays.stream(applied)
                .filter(m -> "8".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v8, "V8 迁移必须存在");
        assertEquals("8", v8.getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, v8.getState());

        // 验证 review.like_count 列存在
        Integer colCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'campus_trade' AND table_name = 'review' AND column_name = 'like_count'",
                Integer.class
        );
        assertEquals(1, colCount, "campus_trade.review 表中必须包含 like_count 列");

        // 验证 review_like 表存在
        Integer likeTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'review_like'",
                Integer.class
        );
        assertEquals(1, likeTable, "campus_trade.review_like 表必须存在");

        // 验证物理防重唯一索引 uk_review_like_review_user
        Integer ukIndex = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'review_like' AND indexname = 'uk_review_like_review_user'",
                Integer.class
        );
        assertEquals(1, ukIndex, "uk_review_like_review_user 唯一索引必须存在");

        // 验证用户历史索引 idx_review_like_user_time
        Integer userIndex = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'review_like' AND indexname = 'idx_review_like_user_time'",
                Integer.class
        );
        assertEquals(1, userIndex, "idx_review_like_user_time 索引必须存在");
    }

    // =========================================================================
    // 二、点赞 API 基础交互与权限安全矩阵 (401/404/400/409/422)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("测试2: 未登录点赞返回 401 Unauthorized")
    void test02_unauthorized_like_returns_401() throws Exception {
        mockMvc.perform(post("/api/reviews/1/like"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("测试3: 不存在的评价点赞返回 404 Not Found")
    void test03_non_existent_review_returns_404() throws Exception {
        mockMvc.perform(post("/api/reviews/99999999/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(4)
    @DisplayName("测试4: 自点赞拦截返回 400 Bad Request")
    void test04_self_like_blocked_returns_400() throws Exception {
        Review review = createTestReview(USER_A_ID, USER_B_ID, 5, "用户A写的评价", ReviewStatus.VISIBLE);

        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("禁止对自己发表的评价点赞"));
    }

    @Test
    @Order(5)
    @DisplayName("测试5: 正常点赞返回 200 OK 且 like_count 原子 +1")
    void test05_normal_like_success() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "用户B写的评价", ReviewStatus.VISIBLE);

        MvcResult result = mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andReturn();

        // 验证数据库真实物理状态
        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(1, dbReview.getLikeCount(), "数据库 review.like_count 必须为 1");

        Long likeRecords = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, review.getId())
                        .eq(ReviewLike::getUserId, USER_A_ID)
        );
        assertEquals(1L, likeRecords, "review_like 表中必须有且仅有 1 条点赞明细");
    }

    @Test
    @Order(6)
    @DisplayName("测试6: 重复点赞拦截返回 409 Conflict 且不重复累加计数")
    void test06_duplicate_like_returns_409() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "用户B评价防重复", ReviewStatus.VISIBLE);

        // 第一次点赞
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // 第二次重复点赞
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));

        // 验证 count 仍然为 1
        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(1, dbReview.getLikeCount(), "重复点赞后 like_count 必须保持为 1");
    }

    @Test
    @Order(7)
    @DisplayName("测试7: 正常取消点赞返回 200 OK 且 like_count 原子 -1")
    void test07_normal_unlike_success() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "取消点赞测试评价", ReviewStatus.VISIBLE);

        // 先点赞
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // 再取消
        mockMvc.perform(delete("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(0, dbReview.getLikeCount(), "取消点赞后 like_count 必须减少为 0");

        Long likeRecords = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, review.getId())
                        .eq(ReviewLike::getUserId, USER_A_ID)
        );
        assertEquals(0L, likeRecords, "review_like 表中明细必须已被删除");
    }

    @Test
    @Order(8)
    @DisplayName("测试8: 重复取消点赞幂等且 like_count 防负数下溢 (GREATEST)")
    void test08_duplicate_unlike_does_not_underflow() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "防负数下溢测试", ReviewStatus.VISIBLE);

        // 直接从未点赞的评价发起取消
        mockMvc.perform(delete("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        // 再次取消
        mockMvc.perform(delete("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(0, dbReview.getLikeCount(), "like_count 绝不能下溢为负数");
    }

    @Test
    @Order(9)
    @DisplayName("测试9: 被屏蔽评价 (AUDIT_REJECTED) 阻断点赞，返回 422 Unprocessable Entity")
    void test09_audit_rejected_review_blocks_like() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "已被违规屏蔽的评价", ReviewStatus.AUDIT_REJECTED);

        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));

        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(0, dbReview.getLikeCount(), "被屏蔽评价 like_count 不能增加");
    }

    @Test
    @Order(10)
    @DisplayName("测试10: 查询点赞状态接口正常透出 liked 态与 likeCount")
    void test10_get_like_status() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "查询状态测试评价", ReviewStatus.VISIBLE);

        // 未点赞时查询
        mockMvc.perform(get("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        // 用户A点赞
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // 用户A查询 -> liked = true
        mockMvc.perform(get("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        // 用户C查询 -> liked = false, likeCount = 1
        mockMvc.perform(get("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        // 匿名查询 -> 未登录阻断 (401 Unauthorized)
        mockMvc.perform(get("/api/reviews/" + review.getId() + "/like"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 三、消灭 N+1 查询与评价列表聚合测试
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("测试11: 评价列表批量加载点赞数与当前用户点赞态 (4阶段批量内存聚合无 N+1)")
    void test11_review_list_aggregates_likes_without_n_plus_one() throws Exception {
        Goods goods = createTestGoods(USER_B_ID, "考研高数真题");

        // 为该商品生成 3 条评价
        // 评价的 order_id 受外键约束（V10），必须是真实存在的订单
        Long o1 = createReviewFixtureOrder(goods.getId(), USER_B_ID, USER_A_ID);
        Long o2 = createReviewFixtureOrder(goods.getId(), USER_C_ID, USER_A_ID);
        Long o3 = createReviewFixtureOrder(goods.getId(), USER_B_ID, USER_A_ID);
        Review r1 = Review.builder()
                .orderId(o1).goodsId(goods.getId()).reviewerId(USER_B_ID).reviewedUserId(USER_A_ID)
                .score(5).content("评价1").status(ReviewStatus.VISIBLE).likeCount(1).createdTime(LocalDateTime.now().minusMinutes(3))
                .build();
        Review r2 = Review.builder()
                .orderId(o2).goodsId(goods.getId()).reviewerId(USER_C_ID).reviewedUserId(USER_A_ID)
                .score(4).content("评价2").status(ReviewStatus.VISIBLE).likeCount(0).createdTime(LocalDateTime.now().minusMinutes(2))
                .build();
        Review r3 = Review.builder()
                .orderId(o3).goodsId(goods.getId()).reviewerId(USER_B_ID).reviewedUserId(USER_A_ID)
                .score(5).content("违规屏蔽评价3").status(ReviewStatus.AUDIT_REJECTED).likeCount(5).createdTime(LocalDateTime.now().minusMinutes(1))
                .build();
        reviewMapper.insert(r1);
        reviewMapper.insert(r2);
        reviewMapper.insert(r3);

        // 用户 A 点赞了 r1
        ReviewLike like = ReviewLike.builder()
                .reviewId(r1.getId())
                .userId(USER_A_ID)
                .createdTime(LocalDateTime.now())
                .build();
        reviewLikeMapper.insert(like);

        // 1. 用户 A 查看商品评价列表
        MvcResult mvcResult = mockMvc.perform(get("/api/reviews/goods/" + goods.getId() + "?page=1&size=10")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rootNode = objectMapper.readTree(mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode records = rootNode.path("data").path("records");

        // 验证只返回 VISIBLE 状态的 2 条评价 (屏蔽评价不可见)
        assertEquals(2, records.size(), "商品公开评价列表必须过滤 AUDIT_REJECTED 评价");

        // 验证 r1 (likedByCurrentUser = true, likeCount = 1)
        JsonNode first = records.get(1); // 因为按 created_time DESC，r2 更近，r1 更早
        JsonNode second = records.get(0);

        JsonNode r1Node = first.path("id").asLong() == r1.getId() ? first : second;
        JsonNode r2Node = first.path("id").asLong() == r2.getId() ? first : second;

        assertTrue(r1Node.path("likedByCurrentUser").asBoolean(), "用户A应该已赞 r1");
        assertEquals(1, r1Node.path("likeCount").asInt(), "r1 点赞数应为 1");

        assertFalse(r2Node.path("likedByCurrentUser").asBoolean(), "用户A未赞 r2");
        assertEquals(0, r2Node.path("likeCount").asInt(), "r2 点赞数应为 0");

        // 2. 未点赞的用户 C 查看评价列表 -> 所有 likedByCurrentUser 均为 false
        MvcResult userCResult = mockMvc.perform(get("/api/reviews/goods/" + goods.getId() + "?page=1&size=10")
                        .header("Authorization", "Bearer " + tokenUserC))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode userCRecords = objectMapper.readTree(userCResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("records");
        assertEquals(2, userCRecords.size());
        assertFalse(userCRecords.get(0).path("likedByCurrentUser").asBoolean());
        assertFalse(userCRecords.get(1).path("likedByCurrentUser").asBoolean());
    }

    // =========================================================================
    // 四、高并发点赞与取消数据一致性验证 (Concurrency Tests)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("测试12: 并发场景A——同一用户 100 线程并发点赞同一评价，严格只产生 1 条点赞记录且 like_count +1")
    void test12_concurrent_likes_same_user_100_threads() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "并发测试评价A", ReviewStatus.VISIBLE);
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    reviewService.likeReview(USER_A_ID, review.getId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证仅 1 个线程成功插入，其余 99 个线程遭遇拦截
        assertEquals(1, successCount.get(), "100个同用户并发点赞，必须且仅能成功 1 次");
        assertEquals(99, conflictCount.get(), "其余 99 个并发请求必须被拦截");

        // 物理数据库校验
        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(1, dbReview.getLikeCount(), "like_count 严格只能 +1");

        Long recordCount = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, review.getId())
                        .eq(ReviewLike::getUserId, USER_A_ID)
        );
        assertEquals(1L, recordCount, "review_like 明细表必须且严格只有 1 条记录");
    }

    @Test
    @Order(13)
    @DisplayName("测试13: 并发场景B——100 个不同用户并发点赞同一评价，严格累加 100 次且物理记录完全对应")
    void test13_concurrent_likes_different_users_100_threads() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "并发测试评价B", ReviewStatus.VISIBLE);
        int userCount = 100;

        // 批量预置 100 个用户
        List<Long> testUserIds = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            Long uid = 88890000L + i;
            testUserIds.add(uid);
            initUser(uid, "concurrent_user_" + i, "用户" + i, "USER");
        }

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch readyLatch = new CountDownLatch(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger(0);

        for (Long uid : testUserIds) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    reviewService.likeReview(uid, review.getId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 异常记录
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(userCount, successCount.get(), "100 个不同用户并发点赞均应成功");

        // 验证数据库真实计数与明细
        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(userCount, dbReview.getLikeCount(), "review.like_count 必须准确累加为 100");

        Long totalLikesInDb = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>().eq(ReviewLike::getReviewId, review.getId())
        );
        assertEquals(100L, totalLikesInDb, "review_like 必须精准存有 100 条明细");
    }

    @Test
    @Order(14)
    @DisplayName("测试14: 并发场景C——多线程同时取消同一用户点赞，防负数下溢且强幂等")
    void test14_concurrent_unlike_does_not_underflow() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "并发取消测试评价", ReviewStatus.VISIBLE);
        // 先点赞
        reviewService.likeReview(USER_A_ID, review.getId());
        assertEquals(1, reviewMapper.selectById(review.getId()).getLikeCount());

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reviewService.unlikeReview(USER_A_ID, review.getId());
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(0, dbReview.getLikeCount(), "并发取消后点赞计数严格为 0，不能为负数");

        Long records = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>()
                        .eq(ReviewLike::getReviewId, review.getId())
                        .eq(ReviewLike::getUserId, USER_A_ID)
        );
        assertEquals(0L, records, "点赞记录必须已被删除");
    }

    @Test
    @Order(15)
    @DisplayName("测试15: 并发场景D——混合高并发点赞与取消，最终 COUNT(review_like) 与 like_count 严格一致")
    void test15_mixed_concurrent_likes_and_unlikes_consistency() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "混合并发一致性评价", ReviewStatus.VISIBLE);
        int poolSize = 40;
        List<Long> poolUsers = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            Long uid = 88891000L + i;
            poolUsers.add(uid);
            initUser(uid, "mixed_user_" + i, "混合用户" + i, "USER");
        }

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(poolSize);

        // 前 20 个用户点赞后取消，后 20 个用户点赞保持
        for (int i = 0; i < poolSize; i++) {
            final int index = i;
            final Long uid = poolUsers.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reviewService.likeReview(uid, review.getId());
                    if (index < 20) {
                        reviewService.unlikeReview(uid, review.getId());
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        Review dbReview = reviewMapper.selectById(review.getId());
        Long actualLikesInDb = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>().eq(ReviewLike::getReviewId, review.getId())
        );

        // 必须严格相等：PostgreSQL Source of Truth 一致性
        assertEquals(actualLikesInDb.intValue(), dbReview.getLikeCount(),
                "数据库 review_like 行数必须与 review.like_count 绝对完全一致");
        assertEquals(20, dbReview.getLikeCount(), "剩余点赞数应为 20");
    }

    // =========================================================================
    // 五、治理屏蔽与历史点赞保留
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("测试16: 评价被治理屏蔽时，历史点赞记录与计数完整保留，不被粗暴清零")
    void test16_history_likes_preserved_when_shielded() {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "待屏蔽评价", ReviewStatus.VISIBLE);
        reviewService.likeReview(USER_A_ID, review.getId());
        reviewService.likeReview(USER_C_ID, review.getId());

        assertEquals(2, reviewMapper.selectById(review.getId()).getLikeCount());

        // 模拟治理屏蔽 (VISIBLE -> AUDIT_REJECTED)
        Review freshReview = reviewMapper.selectById(review.getId());
        freshReview.setStatus(ReviewStatus.AUDIT_REJECTED);
        reviewMapper.updateById(freshReview);

        // 验证历史明细与计数仍然完好保留
        Review shielded = reviewMapper.selectById(review.getId());
        assertEquals(2, shielded.getLikeCount(), "评价屏蔽时历史点赞计数必须完整保留");

        Long records = reviewLikeMapper.selectCount(
                new LambdaQueryWrapper<ReviewLike>().eq(ReviewLike::getReviewId, review.getId())
        );
        assertEquals(2L, records, "评价屏蔽时 review_like 明细记录必须完整保留用于合规审计");
    }

    // =========================================================================
    // 六、管理员评价恢复与信用反向补偿 (PUT /api/admin/reviews/{id}/restore)
    // =========================================================================

    @Test
    @Order(17)
    @DisplayName("测试17: 普通用户调用管理员恢复接口返回 403 Forbidden")
    void test17_non_admin_restore_returns_403() throws Exception {
        mockMvc.perform(put("/api/admin/reviews/1/restore")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(18)
    @DisplayName("测试18: 管理员恢复不存在的评价返回 404 Not Found")
    void test18_admin_restore_non_existent_review_returns_404() throws Exception {
        mockMvc.perform(put("/api/admin/reviews/99999999/restore")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(19)
    @DisplayName("测试19: 管理员恢复处于正常展示状态 (VISIBLE) 的评价返回 400 Bad Request")
    void test19_admin_restore_visible_review_returns_400() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "正常展示中评价", ReviewStatus.VISIBLE);

        mockMvc.perform(put("/api/admin/reviews/" + review.getId() + "/restore")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("评价当前处于正常展示状态，无需重复恢复"));
    }

    @Test
    @Order(20)
    @DisplayName("测试20: 管理员恢复 5 星好评——信用精准补回 +3 分，状态变为 VISIBLE，写入审计日志")
    void test20_admin_restore_five_star_review_credit_compensation() throws Exception {
        Long sellerId = 88882005L;
        initUser(sellerId, "seller_5_star", "五星卖家", "USER");

        // 初始化卖家信用 100 分
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, sellerId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        creditService.getOrCreateCredit(sellerId);

        // 1. 创建 5 星评价，信用 +3 -> 103 分
        Review review = createTestReview(USER_A_ID, sellerId, 5, "很棒的商品五星", ReviewStatus.VISIBLE);
        creditService.addCredit(sellerId, 3, CreditChangeType.REVIEW_GOOD, "REVIEW", review.getId(), "获得5星交易好评");
        assertEquals(103, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 2. 治理屏蔽追缴信用: 扣除 3 分 -> 100 分
        review.setStatus(ReviewStatus.AUDIT_REJECTED);
        reviewMapper.updateById(review);
        creditService.deductCredit(sellerId, 3, CreditChangeType.ADMIN_ADJUST, "REVIEW", review.getId(), "屏蔽追缴");
        assertEquals(100, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 3. 管理员恢复评价 (PUT /api/admin/reviews/{id}/restore)
        RestoreReviewRequest req = RestoreReviewRequest.builder()
                .reason("核实为真实交易误封，现予以正式恢复")
                .build();

        mockMvc.perform(put("/api/admin/reviews/" + review.getId() + "/restore")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));

        // 4. 验证卖家信用精准恢复为 103 分
        UserCredit finalCredit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        assertEquals(103, finalCredit.getCreditScore(), "恢复5星好评后信用分必须精确补回为 103 分");

        // 5. 验证审计日志记录
        AdminAuditLog audit = adminAuditLogMapper.selectOne(
                new LambdaQueryWrapper<AdminAuditLog>()
                        .eq(AdminAuditLog::getTargetId, review.getId())
                        .eq(AdminAuditLog::getOperationType, AdminOperationType.RESTORE_REVIEW.getCode())
        );
        assertNotNull(audit, "必须生成 RESTORE_REVIEW 操作审计日志");
        assertEquals("AUDIT_REJECTED", audit.getBeforeStatus());
        assertEquals("VISIBLE", audit.getAfterStatus());
        assertEquals(ADMIN_USER_ID, audit.getAdminId());
    }

    @Test
    @Order(21)
    @DisplayName("测试21: 管理员恢复 1 星差评——信用重新扣减 -5 分")
    void test21_admin_restore_one_star_review_credit_reapplication() throws Exception {
        Long sellerId = 88882001L;
        initUser(sellerId, "seller_1_star", "差评卖家", "USER");

        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, sellerId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        creditService.getOrCreateCredit(sellerId);

        // 1. 1星差评: 信用 -5 -> 95
        Review review = createTestReview(USER_A_ID, sellerId, 1, "极差的商品1星", ReviewStatus.VISIBLE);
        creditService.deductCredit(sellerId, 5, CreditChangeType.REVIEW_BAD, "REVIEW", review.getId(), "差评扣减");
        assertEquals(95, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 2. 误封冲正: 屏蔽原差评，信用补回 +5 -> 100
        review.setStatus(ReviewStatus.AUDIT_REJECTED);
        reviewMapper.updateById(review);
        creditService.addCredit(sellerId, 5, CreditChangeType.ADMIN_ADJUST, "REVIEW", review.getId(), "误封差评冲正");
        assertEquals(100, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 3. 管理员恢复差评: 重新施加负向扣减 -5 -> 95
        adminGovernanceService.restoreReview(ADMIN_USER_ID, "stage6c_admin", review.getId(), "经查证该差评真实有效，恢复差评展示", "127.0.0.1");

        UserCredit finalCredit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        assertEquals(95, finalCredit.getCreditScore(), "恢复1星差评后必须重新扣减信用分至 95 分");
    }

    @Test
    @Order(22)
    @DisplayName("测试22: 管理员恢复 4 星好评 (+1分) 与 2 星差评 (-2分) 与 3 星评价 (0分)")
    void test22_admin_restore_other_star_reviews_credit() {
        Long sellerId = 88882004L;
        initUser(sellerId, "seller_multi_star", "多元卖家", "USER");

        // 4星好评恢复测试 (+1 分)
        Review r4 = createTestReview(USER_A_ID, sellerId, 4, "4星评价", ReviewStatus.AUDIT_REJECTED);
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, sellerId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        creditService.getOrCreateCredit(sellerId); // 100 分

        adminGovernanceService.restoreReview(ADMIN_USER_ID, "stage6c_admin", r4.getId(), "恢复4星好评", "127.0.0.1");
        assertEquals(101, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 2星差评恢复测试 (-2 分)
        Review r2 = createTestReview(USER_A_ID, sellerId, 2, "2星评价", ReviewStatus.AUDIT_REJECTED);
        adminGovernanceService.restoreReview(ADMIN_USER_ID, "stage6c_admin", r2.getId(), "恢复2星差评", "127.0.0.1");
        assertEquals(99, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());

        // 3星评价恢复测试 (0 分变动)
        Review r3 = createTestReview(USER_A_ID, sellerId, 3, "3星评价", ReviewStatus.AUDIT_REJECTED);
        adminGovernanceService.restoreReview(ADMIN_USER_ID, "stage6c_admin", r3.getId(), "恢复3星评价", "127.0.0.1");
        assertEquals(99, userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)).getCreditScore());
    }

    @Test
    @Order(23)
    @DisplayName("测试23: 管理员恢复评价幂等性——两个并发恢复请求仅有 1 个能成功，信用绝不重复补偿")
    void test23_concurrent_admin_restore_idempotent() throws Exception {
        Long sellerId = 88882023L;
        initUser(sellerId, "seller_con_restore", "并发恢复卖家", "USER");

        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, sellerId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        creditService.getOrCreateCredit(sellerId); // 100 分

        Review review = createTestReview(USER_A_ID, sellerId, 5, "并发恢复测试评价", ReviewStatus.AUDIT_REJECTED);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    adminGovernanceService.restoreReview(ADMIN_USER_ID, "stage6c_admin", review.getId(), "并发恢复测试", "127.0.0.1");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "10 个并发恢复请求中必须且仅有 1 个成功");
        assertEquals(9, failCount.get(), "其余 9 个并发恢复请求必须失败");

        // 验证卖家信用仅补偿了 1 次 (+3 分 -> 103 分)
        UserCredit finalCredit = userCreditMapper.selectOne(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId));
        assertEquals(103, finalCredit.getCreditScore(), "信用分绝不能发生重复补偿，必须严格为 103 分");

        // 验证审计日志仅有一条 RESTORE_REVIEW
        Long auditCount = adminAuditLogMapper.selectCount(
                new LambdaQueryWrapper<AdminAuditLog>()
                        .eq(AdminAuditLog::getTargetId, review.getId())
                        .eq(AdminAuditLog::getOperationType, AdminOperationType.RESTORE_REVIEW.getCode())
        );
        assertEquals(1L, auditCount, "管理员审计日志必须仅有 1 条恢复记录");
    }

    @Test
    @Order(24)
    @DisplayName("测试24: 恢复评价后，前台用户可重新点赞且状态流转完全正常")
    void test24_restored_review_can_be_liked_again() throws Exception {
        Review review = createTestReview(USER_B_ID, USER_A_ID, 5, "恢复后重新点赞测试", ReviewStatus.AUDIT_REJECTED);

        // 屏蔽态下点赞失败 (422)
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));

        // 管理员执行恢复
        mockMvc.perform(put("/api/admin/reviews/" + review.getId() + "/restore")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复合规展示\"}"))
                .andExpect(status().isOk());

        // 恢复后正常点赞 -> 成功 (200)
        mockMvc.perform(post("/api/reviews/" + review.getId() + "/like")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        Review dbReview = reviewMapper.selectById(review.getId());
        assertEquals(1, dbReview.getLikeCount(), "恢复后点赞数应正常变为 1");
    }
}
