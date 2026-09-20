package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.report.ReportQueryRequest;
import com.campustrade.entity.*;
import com.campustrade.enums.AdminOperationType;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReportReasonType;
import com.campustrade.enums.ReportStatus;
import com.campustrade.enums.ReportTargetType;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.mapper.*;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.CreditService;
import com.campustrade.service.ReportService;
import com.campustrade.vo.report.AdminAuditLogVO;
import com.campustrade.vo.report.AdminReportDetailVO;
import com.campustrade.vo.report.ReportVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 6-B: 举报工单系统与管理员治理后端自动化测试套件
 * 严格覆盖：Flyway V7、安全矩阵、参数校验、自举报拦截、防重复工单(409)、
 * 每日限频(429)、多态目标处置、评价屏蔽与信用冲正、管理员审计日志、状态机防重等全链路场景
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage6BTests {

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
    private ReportMapper reportMapper;

    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private AdminGovernanceService adminGovernanceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long REPORTER_USER_ID = 88880001L;
    private static final Long SELLER_USER_ID = 88880002L;
    private static final Long ADMIN_USER_ID = 88880099L;
    private static final Long REVIEWED_USER_ID = 88880003L;

    private static String userToken;
    private static String adminToken;

    @BeforeEach
    void setUpTestData() {
        initUser(REPORTER_USER_ID, "stage6b_reporter", "举报人同学", "USER");
        initUser(SELLER_USER_ID, "stage6b_seller", "卖家同学", "USER");
        initUser(REVIEWED_USER_ID, "stage6b_reviewed", "被评人同学", "USER");
        initUser(ADMIN_USER_ID, "stage6b_admin", "平台超级管理员", "ADMIN");

        userToken = jwtTokenProvider.generateAccessToken(REPORTER_USER_ID, "stage6b_reporter", "USER");
        adminToken = jwtTokenProvider.generateAccessToken(ADMIN_USER_ID, "stage6b_admin", "ADMIN");

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        stringRedisTemplate.delete("report:daily:limit:" + REPORTER_USER_ID + ":" + today);
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
        } else {
            credit.setCreditScore(100);
            credit.setCreditLevel("GOOD");
            userCreditMapper.updateById(credit);
        }
    }

    private Goods createTestGoods(Long sellerId, String title, String status) {
        Goods g = Goods.builder()
                .sellerId(sellerId)
                .schoolId(1L)
                .categoryId(1L)
                .title(title)
                .description("测试商品详细描述")
                .price(new BigDecimal("299.00"))
                .originalPrice(new BigDecimal("599.00"))
                .conditionLevel("95新")
                .status(status)
                .viewCount(10)
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
            reviewFixtureGoodsId = createTestGoods(SELLER_USER_ID, "评价外键夹具商品", "ON_SALE").getId();
        }
        return reviewFixtureGoodsId;
    }

    /** 为评价夹具创建一条真实存在的 COMPLETED 订单（非活动状态，不受局部唯一索引限制）。 */
    private Long createReviewFixtureOrder(Long goodsId, Long buyerId, Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = TradeOrder.builder()
                .orderNo("ORD_FIXTURE6B_" + UUID.randomUUID().toString().substring(0, 8))
                .buyerId(buyerId)
                .sellerId(sellerId)
                .goodsId(goodsId)
                .goodsTitleSnapshot("评价外键夹具商品")
                .goodsPriceSnapshot(new BigDecimal("299.00"))
                .meetLocation("测试地点")
                .orderStatus(OrderStatus.COMPLETED)
                .completedTime(now)
                .createdTime(now)
                .updatedTime(now)
                .build();
        tradeOrderMapper.insert(order);
        return order.getId();
    }

    private Review createTestReview(Long reviewerId, Long reviewedUserId, Integer score, String content) {
        Long fixtureGoodsId = ensureReviewFixtureGoods();
        Long fixtureOrderId = createReviewFixtureOrder(fixtureGoodsId, reviewerId, reviewedUserId);
        Review r = Review.builder()
                .orderId(fixtureOrderId)
                .goodsId(fixtureGoodsId)
                .reviewerId(reviewerId)
                .reviewedUserId(reviewedUserId)
                .score(score)
                .content(content)
                .status(ReviewStatus.VISIBLE)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        reviewMapper.insert(r);
        return r;
    }

    // =========================================================================
    // 一、Flyway V7 数据库迁移与数据表结构验证
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("测试1: Flyway V7 迁移成功应用，report 与 admin_audit_log 表和索引完备")
    void test01_flyway_v7_applied_successfully() {
        assertNotNull(flyway);
        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 7, "至少应已应用 7 个 Flyway migrations");

        MigrationInfo v7 = Arrays.stream(applied)
                .filter(m -> "7".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v7, "V7 迁移必须存在");
        assertEquals("7", v7.getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, v7.getState());

        // 验证表存在
        Integer reportTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'report'",
                Integer.class
        );
        assertEquals(1, reportTable, "campus_trade.report 表必须存在");

        Integer auditTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'admin_audit_log'",
                Integer.class
        );
        assertEquals(1, auditTable, "campus_trade.admin_audit_log 表必须存在");

        // 验证物理防重唯一部分索引
        Integer activeIndex = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'report' AND indexname = 'uk_report_active'",
                Integer.class
        );
        assertEquals(1, activeIndex, "uk_report_active 唯一部分索引必须存在");
    }

    // =========================================================================
    // 二、接口权限控制与安全矩阵 (401 Unauthorized / 403 Forbidden)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("测试2: 未登录用户调用举报接口返回 401 Unauthorized")
    void test02_unauthorized_access_reports() throws Exception {
        CreateReportRequest req = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(12345L)
                .reasonType("FRAUD")
                .description("未登录举报测试")
                .build();

        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    @DisplayName("测试3: 普通用户尝试访问管理后台接口返回 403 Forbidden")
    void test03_regular_user_forbidden_admin_apis() throws Exception {
        mockMvc.perform(get("/api/admin/reports")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("VALID")
                .note("越权处理尝试")
                .build();

        mockMvc.perform(put("/api/admin/reports/1/handle")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    @DisplayName("测试4: 管理员正常访问后台接口返回 200 OK")
    void test04_admin_authorized_access() throws Exception {
        mockMvc.perform(get("/api/admin/reports")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    // =========================================================================
    // 三、举报提交参数校验、不存在实体与自举报拦截 (400 / 404)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("测试5: 提交不存在的目标实体返回 404 Not Found")
    void test05_report_non_existent_target_returns_404() throws Exception {
        CreateReportRequest req = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(999999999L)
                .reasonType("FRAUD")
                .description("该商品不存在")
                .build();

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(6)
    @DisplayName("测试6: 严禁自举报防范 (卖家举报自己商品 / 评价者举报自己评价 / 用户举报自己)")
    void test06_self_report_defense() throws Exception {
        // 1. 卖家举报自己的商品
        Goods ownGoods = createTestGoods(REPORTER_USER_ID, "自己发布的测试商品", "ON_SALE");
        CreateReportRequest goodsReq = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(ownGoods.getId())
                .reasonType("FRAUD")
                .description("尝试举报自己的商品")
                .build();

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(goodsReq)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能举报自己发布的商品"));

        // 2. 评价者举报自己发表的评价
        Review ownReview = createTestReview(REPORTER_USER_ID, SELLER_USER_ID, 5, "自己发表的五星好评");
        CreateReportRequest reviewReq = CreateReportRequest.builder()
                .targetType("REVIEW")
                .targetId(ownReview.getId())
                .reasonType("MALICIOUS_REVIEW")
                .description("尝试举报自己的评价")
                .build();

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewReq)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能举报自己发表的评价"));

        // 3. 用户举报自己
        CreateReportRequest userReq = CreateReportRequest.builder()
                .targetType("USER")
                .targetId(REPORTER_USER_ID)
                .reasonType("HARASSMENT")
                .description("尝试举报自己")
                .build();

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不能举报自己"));
    }

    // =========================================================================
    // 四、正常举报提交流程与待处理防重拦截 (200 / 409)
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("测试7: 正常提交举报工单，初始状态为 PENDING")
    void test07_submit_report_success() throws Exception {
        Goods goods = createTestGoods(SELLER_USER_ID, "违规山寨iPhone", "ON_SALE");

        CreateReportRequest req = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(goods.getId())
                .reasonType("COUNTERFEIT")
                .description("商品涉嫌高仿山寨，与正品严重不符")
                .evidenceImages("https://img.test/evidence1.jpg,https://img.test/evidence2.jpg")
                .build();

        MvcResult result = mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetType").value("GOODS"))
                .andExpect(jsonPath("$.data.targetId").value(goods.getId()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.reasonDesc").value("假冒劣质"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long reportId = json.path("data").path("id").asLong();
        assertTrue(reportId > 0);

        // 验证用户端查询我的举报
        mockMvc.perform(get("/api/reports/my")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value(reportId));
    }

    @Test
    @Order(8)
    @DisplayName("测试8: 同一用户对同一目标未结工单重复举报触发 409 Conflict 拦截")
    void test08_duplicate_pending_report_conflict() throws Exception {
        Goods goods = createTestGoods(SELLER_USER_ID, "重复举报测试商品", "ON_SALE");

        CreateReportRequest req = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(goods.getId())
                .reasonType("FRAUD")
                .description("第一次举报")
                .build();

        // 第一次成功
        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 第二次重复提交处于 PENDING 的同一目标
        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该目标您已提交举报，管理员正在核查中"));
    }

    // =========================================================================
    // 五、Redis 每日频控限流 (429 Too Many Requests)
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("测试9: 单个用户每日举报超过 10 次触发 429 Too Many Requests 限频")
    void test09_daily_rate_limit_exceeded() throws Exception {
        Long limitUserId = 88880010L;
        initUser(limitUserId, "stage6b_limit_user", "高频用户", "USER");
        String limitToken = jwtTokenProvider.generateAccessToken(limitUserId, "stage6b_limit_user", "USER");

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String redisKey = "report:daily:limit:" + limitUserId + ":" + today;

        // 模拟今日已经举报了 10 次
        stringRedisTemplate.opsForValue().set(redisKey, "10");

        Goods goods = createTestGoods(SELLER_USER_ID, "限流测试商品", "ON_SALE");
        CreateReportRequest req = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(goods.getId())
                .reasonType("OTHER")
                .description("第11次举报")
                .build();

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", "Bearer " + limitToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("您今日举报次数已达上限，请明天再试"));

        // 清理缓存
        stringRedisTemplate.delete(redisKey);
    }

    // =========================================================================
    // 六、管理员治理工单处理与目标违规下架 / 审计流水
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("测试10: 管理员驳回无效举报工单 (INVALID) 并记录审计流水")
    void test10_admin_reject_invalid_report() throws Exception {
        Goods goods = createTestGoods(SELLER_USER_ID, "被误报的正常书籍", "ON_SALE");

        CreateReportRequest createReq = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(goods.getId())
                .reasonType("FRAUD")
                .description("买家恶意投诉价格过高")
                .build();

        ReportVO reportVO = reportService.submitReport(REPORTER_USER_ID, createReq);
        Long reportId = reportVO.getId();

        // 管理员驳回
        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("INVALID")
                .note("核查价格符合校园市场二手标准，举报不属实，予以驳回")
                .build();

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("HANDLED_INVALID"))
                .andExpect(jsonPath("$.data.handleResult").value("核查价格符合校园市场二手标准，举报不属实，予以驳回"))
                .andExpect(jsonPath("$.data.handledBy").value(ADMIN_USER_ID));

        // 商品状态仍保持 ON_SALE
        Goods checkGoods = goodsMapper.selectById(goods.getId());
        assertEquals("ON_SALE", checkGoods.getStatus());

        // 验证审计日志
        Long auditCount = adminAuditLogMapper.selectCount(
                new LambdaQueryWrapper<AdminAuditLog>()
                        .eq(AdminAuditLog::getAdminId, ADMIN_USER_ID)
                        .eq(AdminAuditLog::getOperationType, AdminOperationType.REJECT_REPORT.getCode())
                        .eq(AdminAuditLog::getTargetId, reportId)
        );
        assertTrue(auditCount >= 1, "必须生成驳回工单的管理员审计流水");
    }

    @Test
    @Order(11)
    @DisplayName("测试11: 管理员采纳违规商品举报 (VALID)，商品强制下架 (OFF_SHELF) 并记录审计流水")
    void test11_admin_accept_goods_report_and_off_shelf() throws Exception {
        Goods illegalGoods = createTestGoods(SELLER_USER_ID, "违禁实验药品", "ON_SALE");

        CreateReportRequest createReq = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(illegalGoods.getId())
                .reasonType("ILLEGAL_PROHIBITED")
                .description("商品包含高校禁止私下买卖的化学实验试剂")
                .build();

        ReportVO reportVO = reportService.submitReport(REPORTER_USER_ID, createReq);
        Long reportId = reportVO.getId();

        // 管理员判定属实并采纳
        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("VALID")
                .note("违规物品属实，依据校规予以紧急强制下架")
                .build();

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("HANDLED_VALID"));

        // 验证商品已被下架
        Goods updatedGoods = goodsMapper.selectById(illegalGoods.getId());
        assertEquals("OFF_SHELF", updatedGoods.getStatus(), "违规商品必须被设置为 OFF_SHELF 状态");

        // 验证针对商品的审计流水
        AdminAuditLog goodsLog = adminAuditLogMapper.selectOne(
                new LambdaQueryWrapper<AdminAuditLog>()
                        .eq(AdminAuditLog::getOperationType, AdminOperationType.OFF_SHELF_GOODS.getCode())
                        .eq(AdminAuditLog::getTargetId, illegalGoods.getId())
        );
        assertNotNull(goodsLog, "必须生成商品强制下架的审计记录");
        assertEquals("ON_SALE", goodsLog.getBeforeStatus());
        assertEquals("OFF_SHELF", goodsLog.getAfterStatus());
    }

    // =========================================================================
    // 七、违规评价屏蔽与信用安全冲正 (Credit Reversal)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("测试12: 违规 5 星好评屏蔽 (AUDIT_REJECTED) 并精确追缴扣除 3 分信用分")
    void test12_shield_good_review_and_reverse_credit() throws Exception {
        // 初始化被评人信用为 100 分
        creditService.getOrCreateCredit(REVIEWED_USER_ID);
        UserCredit beforeCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, REVIEWED_USER_ID)
        );
        int initialScore = beforeCredit.getCreditScore();

        Review goodReview = createTestReview(SELLER_USER_ID, REVIEWED_USER_ID, 5, "虚假刷单5星好评");

        CreateReportRequest createReq = CreateReportRequest.builder()
                .targetType("REVIEW")
                .targetId(goodReview.getId())
                .reasonType("MALICIOUS_REVIEW")
                .description("两方私下串通虚假刷单刷好评")
                .build();

        ReportVO reportVO = reportService.submitReport(REPORTER_USER_ID, createReq);
        Long reportId = reportVO.getId();

        // 管理员判定属实屏蔽
        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("VALID")
                .note("虚假刷单好评判定属实，屏蔽评价并追缴其所获积分")
                .build();

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证评价被屏蔽
        Review updatedReview = reviewMapper.selectById(goodReview.getId());
        assertEquals(ReviewStatus.AUDIT_REJECTED, updatedReview.getStatus(), "违规评价必须标记为 AUDIT_REJECTED");

        // 验证被评人信用扣除 3 分
        UserCredit afterCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, REVIEWED_USER_ID)
        );
        assertEquals(initialScore - 3, afterCredit.getCreditScore(), "5星好评屏蔽必须精确追缴 3 分");

        // 验证 user_credit_log 记录了 ADMIN_ADJUST
        UserCreditLog creditLog = userCreditLogMapper.selectOne(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, REVIEWED_USER_ID)
                        .eq(UserCreditLog::getRelatedType, "REVIEW")
                        .eq(UserCreditLog::getRelatedId, goodReview.getId())
                        .eq(UserCreditLog::getChangeType, "ADMIN_ADJUST")
        );
        assertNotNull(creditLog, "必须生成 ADMIN_ADJUST 信用冲正流水");
        assertEquals(-3, creditLog.getChangeScore());
    }

    @Test
    @Order(13)
    @DisplayName("测试13: 违规 1 星差评屏蔽 (AUDIT_REJECTED) 并精确补回 5 分信用分")
    void test13_shield_bad_review_and_restore_credit() throws Exception {
        creditService.getOrCreateCredit(REVIEWED_USER_ID);
        UserCredit beforeCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, REVIEWED_USER_ID)
        );
        int initialScore = beforeCredit.getCreditScore();

        Review badReview = createTestReview(SELLER_USER_ID, REVIEWED_USER_ID, 1, "恶意差评与人身攻击辱骂");

        CreateReportRequest createReq = CreateReportRequest.builder()
                .targetType("REVIEW")
                .targetId(badReview.getId())
                .reasonType("HARASSMENT")
                .description("该评价包含人身攻击与恶意造谣诽谤")
                .build();

        ReportVO reportVO = reportService.submitReport(REPORTER_USER_ID, createReq);
        Long reportId = reportVO.getId();

        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("VALID")
                .note("恶意辱骂差评属实，予以屏蔽并恢复被评人信用分")
                .build();

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isOk());

        // 验证评价被屏蔽
        Review updatedReview = reviewMapper.selectById(badReview.getId());
        assertEquals(ReviewStatus.AUDIT_REJECTED, updatedReview.getStatus());

        // 验证信用补回 5 分
        UserCredit afterCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, REVIEWED_USER_ID)
        );
        assertEquals(initialScore + 5, afterCredit.getCreditScore(), "1星恶意差评屏蔽必须精确补回 5 分");
    }

    // =========================================================================
    // 八、工单状态机防重复处理与审计流水查询
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("测试14: 工单状态机闭环防重，已处理工单不可再次重复处理 (400)")
    void test14_cannot_handle_already_processed_report() throws Exception {
        Goods goods = createTestGoods(SELLER_USER_ID, "状态机测试商品", "ON_SALE");
        CreateReportRequest createReq = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(goods.getId())
                .reasonType("OTHER")
                .description("状态机测试工单")
                .build();

        ReportVO reportVO = reportService.submitReport(REPORTER_USER_ID, createReq);
        Long reportId = reportVO.getId();

        HandleReportRequest handleReq = HandleReportRequest.builder()
                .action("INVALID")
                .note("第一次已处理驳回")
                .build();

        // 第一次处理成功
        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(status().isOk());

        // 第二次重复尝试处理
        mockMvc.perform(put("/api/admin/reports/" + reportId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(handleReq)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("工单已被处理，不可重复处理"));
    }

    @Test
    @Order(15)
    @DisplayName("测试15: 管理员查询审计流水列表分页接口正常返回")
    void test15_query_admin_audit_logs() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs?page=1&size=5")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].adminUsername").exists())
                .andExpect(jsonPath("$.data.records[0].operationDesc").exists());
    }
}
