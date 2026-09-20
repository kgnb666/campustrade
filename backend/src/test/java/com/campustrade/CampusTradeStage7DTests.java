package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.CreditRule;
import com.campustrade.common.util.HtmlEscapeUtils;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.Review;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditLevel;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.event.ReviewCreatedEvent;
import com.campustrade.exception.BusinessException;
import com.campustrade.exception.OrderBusinessException;
import com.campustrade.listener.CreditReviewEventListener;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.CreditService;
import com.campustrade.service.ReviewService;
import com.campustrade.support.TestCredentials;
import com.campustrade.enums.ReportStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 7-D：契约与领域模型收敛验收套件。
 *
 * <p>覆盖本次收敛的四件事，每件都有可复核的断言：</p>
 * <ol>
 *   <li><b>错误语义唯一</b>：业务码 → 真实 HTTP 状态码（映射只在
 *       {@code BusinessException#httpStatus()}）；未匹配路径 404 而不是 500；
 *       响应体结构始终保持 {@code {code,message,data,timestamp}}。</li>
 *   <li><b>服务层不再返回 Web 信封</b>：登录接口不会返回"成功但 userInfo 为空"。</li>
 *   <li><b>状态枚举化</b>：商品状态字面量只存在于 {@link GoodsStatus}。</li>
 *   <li><b>信用规则集中化</b>：星级分值 / 7 天窗口 / 分区间 / 完成订单加分只在
 *       {@link CreditRule}，且"加分 → 冲正 → 恢复"三条路径对称（净漂移为 0）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampusTradeStage7DTests {

    private static final Long SELLER_ID = 9300001L;
    private static final Long BUYER_ID = 9300002L;
    private static final Long THIRD_PARTY_ID = 9300003L;
    private static final Long ADMIN_ID = 9300099L;

    private static final String SELLER_USERNAME = "stage7d_seller";
    private static final String BUYER_USERNAME = "stage7d_buyer";
    private static final String THIRD_USERNAME = "stage7d_third";
    private static final String ADMIN_USERNAME = "stage7d_admin";

    /** 请求体/响应体的固定字段集合（结构不变性的断言依据）。 */
    private static final Set<String> ENVELOPE_FIELDS = Set.of("code", "message", "data", "timestamp");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private AdminGovernanceService adminGovernanceService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private CreditReviewEventListener creditReviewEventListener;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdGoodsIds = new ArrayList<>();

    private final List<String> createdUsernames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        initUser(SELLER_ID, SELLER_USERNAME, "卖家七丁", "USER");
        initUser(BUYER_ID, BUYER_USERNAME, "买家七丁", "USER");
        initUser(THIRD_PARTY_ID, THIRD_USERNAME, "路人七丁", "USER");
        initUser(ADMIN_ID, ADMIN_USERNAME, "管理员七丁", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        for (Long goodsId : createdGoodsIds) {
            jdbcTemplate.update("DELETE FROM campus_trade.report WHERE target_type = 'GOODS' AND target_id = ?", goodsId);
            jdbcTemplate.update("DELETE FROM campus_trade.review WHERE goods_id = ?", goodsId);
            jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE goods_id = ?", goodsId);
            jdbcTemplate.update("DELETE FROM campus_trade.goods_image WHERE goods_id = ?", goodsId);
            jdbcTemplate.update("DELETE FROM campus_trade.goods WHERE id = ?", goodsId);
        }
        createdGoodsIds.clear();

        // V12 起 report.reporter_id / admin_audit_log.admin_id / review.* 对 user 有外键：
        // 删除夹具用户之前必须先把指向它们的举报工单、审计流水与评价清掉
        // （评价类目标的工单不在上面按商品 ID 的清理范围内，因此这里按"涉及的用户"统一清理）。
        jdbcTemplate.update("DELETE FROM campus_trade.admin_audit_log WHERE admin_id = ?", ADMIN_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.report WHERE reporter_id IN (?, ?, ?, ?) OR handled_by IN (?, ?, ?, ?)",
                SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID, SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.review_like WHERE user_id IN (?, ?, ?, ?)",
                SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.review WHERE reviewer_id IN (?, ?, ?, ?) OR reviewed_user_id IN (?, ?, ?, ?)",
                SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID, SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.user_credit_log WHERE user_id IN (?, ?, ?, ?)",
                SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.user_credit WHERE user_id IN (?, ?, ?, ?)",
                SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID);
        for (String username : createdUsernames) {
            // 注册接口会为新用户建立信用档案，而 V12 起 user_credit.user_id 对 user 有外键：
            // 先按用户名定位并清理子表，再删用户，否则删除会被外键拒绝。
            jdbcTemplate.update("DELETE FROM campus_trade.user_credit_log WHERE user_id IN "
                    + "(SELECT id FROM campus_trade.\"user\" WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM campus_trade.user_credit WHERE user_id IN "
                    + "(SELECT id FROM campus_trade.\"user\" WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM campus_trade.user WHERE username = ?", username);
        }
        createdUsernames.clear();
        for (Long id : new Long[]{SELLER_ID, BUYER_ID, THIRD_PARTY_ID, ADMIN_ID}) {
            userMapper.deleteById(id);
        }
    }

    // =========================================================================
    // 1. 错误语义：业务码 → 真实 HTTP 状态码
    // =========================================================================

    @Test
    @DisplayName("1. 业务码 → HTTP 状态映射表是唯一真相源（含未知码兜底 500）")
    void test01_businessCodeMapsToRealHttpStatus() {
        int[] mapped = {400, 401, 403, 404, 405, 409, 422, 429, 500};
        for (int code : mapped) {
            BusinessException ex = new BusinessException(code, "业务码 " + code);
            assertEquals(code, ex.httpStatus().value(),
                    "业务码 " + code + " 必须映射为同名 HTTP 状态");
        }

        // 未纳入白名单的业务码一律落到 500，绝不把任意整数写进 HTTP 状态行
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, new BusinessException(418, "我是茶壶").httpStatus());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, new BusinessException(99999, "越界码").httpStatus());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, new BusinessException("无业务码的默认异常").httpStatus());

        // 订单业务异常继承同一映射，不再需要 Controller 逐处 catch + rethrow
        assertEquals(HttpStatus.FORBIDDEN, new OrderBusinessException(403, "只有卖家可以确认订单").httpStatus());
        assertEquals(HttpStatus.NOT_FOUND, new OrderBusinessException(404, "订单不存在").httpStatus());
    }

    @Test
    @DisplayName("2. 未匹配路径返回 404（此前兜底 500），响应体结构不变")
    void test02_unmappedPathReturns404NotServerError() throws Exception {
        // permitAll 的路径前缀 + 不存在的子路径：匿名请求直达 DispatcherServlet
        mockMvc.perform(get("/school/profilex"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("请求的资源不存在")));

        mockMvc.perform(get("/api/school/profilex"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        // 需要认证的路径前缀 + 不存在的子路径：带合法令牌时必须得到 404（此前是 500）
        String token = jwtTokenProvider.generateAccessToken(BUYER_ID, BUYER_USERNAME, "USER");
        mockMvc.perform(get("/api/user/profilex").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/user/profilex").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        // 同一路径在匿名请求下由鉴权层先拦截为 401（这是安全边界，不是 500）
        mockMvc.perform(get("/api/user/profilex"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("3. 普通用户访问他人订单：真实 HTTP 403，响应体仍是 code/message/data/timestamp")
    void test03_foreignResourceReturnsRealHttp403WithUnchangedBody() throws Exception {
        Long goodsId = createGoods(GoodsStatus.SOLD);
        TradeOrder order = createOrder(goodsId, OrderStatus.WAIT_SELLER_CONFIRM);

        String thirdToken = jwtTokenProvider.generateAccessToken(THIRD_PARTY_ID, THIRD_USERNAME, "USER");

        String body = mockMvc.perform(get("/orders/" + order.getId())
                        .header("Authorization", "Bearer " + thirdToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该订单详情"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode node = objectMapper.readTree(body);
        assertEquals(ENVELOPE_FIELDS, toFieldSet(node), "响应体字段集合必须保持 code/message/data/timestamp");
        assertTrue(node.path("timestamp").asLong() > 0L, "timestamp 必须存在且为正数");

        // 非当事人取消订单同样是真 403（每个订单接口都不再依赖 Controller 里的 try/catch 兜底）
        mockMvc.perform(put("/orders/" + order.getId() + "/cancel")
                        .header("Authorization", "Bearer " + thirdToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"不是我买的\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("只有买家或卖家可以取消订单"));
    }

    @Test
    @DisplayName("4. 登录接口不会返回“成功但 userInfo 为空”")
    void test04_loginNeverReturnsEmptyUserInfo() throws Exception {
        String username = "stage7d_login_" + UUID.randomUUID().toString().substring(0, 6);
        String password = TestCredentials.randomPassword();
        createdUsernames.add(username);

        RegisterRequestDTO register = new RegisterRequestDTO();
        register.setUsername(username);
        register.setPassword(password);
        register.setEmail(username + "@example.com");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        LoginRequestDTO login = new LoginRequestDTO();
        login.setUsername(username);
        login.setPassword(password);
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.username").value(username))
                .andExpect(jsonPath("$.data.userInfo.credit.creditScore").value(CreditRule.SCORE_DEFAULT))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertNotNull(objectMapper.readTree(body).path("data").path("userInfo").get("id"),
                "userInfo 必须携带真实用户 ID");
    }

    // =========================================================================
    // 2. 商品状态枚举化
    // =========================================================================

    @Test
    @DisplayName("5. GoodsStatus 与 V10 CHECK 约束取值域一致，且状态字面量只来自枚举")
    void test05_goodsStatusIsTheOnlySourceOfStatusLiterals() {
        // 这里的字面量**刻意**保留（本批次唯一保留状态字面量的地方）：
        // 它们是"被比对的外部真相"——V10 迁移里 CHECK 约束的取值域，而不是又一次抄枚举。
        // 若有人改动 GoodsStatus 的常量名，这条断言会立即失败（并连带提醒需要新迁移同步数据库约束），
        // 因此它不属于"枚举改名后测试静默漏改"那一类风险；改成 GoodsStatus.*.getCode() 自比只会变成恒真。
        assertEquals(Set.of("DRAFT", "ON_SALE", "LOCKED", "SOLD", "OFF_SHELF"),
                Set.of(GoodsStatus.allCodes()), "枚举取值必须与 V10 CHECK 约束完全一致");

        // code 就是枚举常量名：不存在"枚举名与字面量各写一遍"的可能
        for (GoodsStatus status : GoodsStatus.values()) {
            assertEquals(status.name(), status.getCode());
        }

        assertEquals(GoodsStatus.ON_SALE, GoodsStatus.fromCode("on_sale"));
        assertEquals(GoodsStatus.OFF_SHELF, GoodsStatus.fromCode("  OFF_SHELF "));
        assertNull(GoodsStatus.fromCode("BOGUS"));
        assertNull(GoodsStatus.fromCode(null));
        assertTrue(GoodsStatus.LOCKED.matches("locked"));
        assertFalse(GoodsStatus.LOCKED.matches(GoodsStatus.ON_SALE.getCode()));
        assertFalse(GoodsStatus.LOCKED.matches(null));

        // 数据库侧 CHECK 约束与枚举必须同域（真实读一次约束定义）
        String constraint = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_goods_status_domain'",
                String.class);
        assertNotNull(constraint, "V10 的 chk_goods_status_domain 约束必须存在");
        for (GoodsStatus status : GoodsStatus.values()) {
            assertTrue(constraint.contains("'" + status.getCode() + "'"),
                    "CHECK 约束必须包含枚举取值 " + status.getCode() + "：" + constraint);
        }
    }

    // =========================================================================
    // 3. 信用规则集中化 + 三条路径对称
    // =========================================================================

    @Test
    @DisplayName("6. CreditRule：星级分值/窗口/分区间/完成加分只此一处，三条路径严格对称")
    void test06_creditRuleIsTheSingleSourceOfTruth() {
        // 数值与既有业务规则逐字一致（本次只集中化，不改数值）
        assertEquals(3, CreditRule.reviewDeltaForScore(5));
        assertEquals(1, CreditRule.reviewDeltaForScore(4));
        assertEquals(0, CreditRule.reviewDeltaForScore(3));
        assertEquals(-2, CreditRule.reviewDeltaForScore(2));
        assertEquals(-5, CreditRule.reviewDeltaForScore(1));

        assertEquals(7, CreditRule.REVIEW_WINDOW_DAYS);
        assertEquals(168L, CreditRule.REVIEW_WINDOW_HOURS);
        assertEquals(0, CreditRule.SCORE_MIN);
        assertEquals(200, CreditRule.SCORE_MAX);
        assertEquals(100, CreditRule.SCORE_DEFAULT);
        assertEquals(2, CreditRule.TRADE_COMPLETED_BONUS);

        // 三条路径的对称性：创建 / 冲正 / 恢复
        for (int star = CreditRule.STAR_MIN; star <= CreditRule.STAR_MAX; star++) {
            int created = CreditRule.reviewDeltaForScore(star);
            int reversal = CreditRule.reviewReversalDeltaForScore(star);
            int restore = CreditRule.reviewRestoreDeltaForScore(star);

            assertEquals(-created, reversal, star + " 星：冲正必须是创建分值的精确取反");
            assertEquals(created, restore, star + " 星：恢复补偿必须与创建分值一致");
            assertEquals(0, created + reversal, star + " 星：创建 + 冲正 必须为 0（被屏蔽的评价不再贡献分数）");
            assertEquals(0, reversal + restore, star + " 星：冲正 + 恢复 必须为 0（一轮治理动作零漂移）");
            // 「加分 → 冲正 → 恢复」回到"评价正常展示"的分数：净变动为 0
            assertEquals(0, created + reversal + restore - created,
                    star + " 星：加分 → 冲正 → 恢复 相对正常展示基线的净变动必须为 0");
        }

        assertTrue(CreditRule.isKnownStar(5));
        assertFalse(CreditRule.isKnownStar(0));
        assertFalse(CreditRule.isKnownStar(6));
        assertThrows(IllegalArgumentException.class, () -> CreditRule.reviewDeltaForScore(6));

        // 7 天窗口判定
        LocalDateTime now = LocalDateTime.now();
        assertFalse(CreditRule.isReviewWindowExpired(now.minusDays(6), now), "6 天仍在窗口内");
        assertFalse(CreditRule.isReviewWindowExpired(now.minusHours(167), now), "167 小时仍在窗口内");
        assertTrue(CreditRule.isReviewWindowExpired(now.minusHours(169), now), "169 小时已超出窗口");
        assertTrue(CreditRule.isReviewWindowExpired(now.minusDays(7).minusSeconds(1), now));
        assertFalse(CreditRule.isReviewWindowExpired(null, now), "完成时间为空时不得判定为过期");
    }

    @Test
    @DisplayName("7. CreditLevel 区间表来自枚举字段并与 CreditRule 的 [0,200] 严格对齐")
    void test07_creditLevelTableCoversCreditRuleRange() {
        assertEquals(CreditRule.SCORE_MIN, CreditLevel.POOR.getMinScore());
        assertEquals(CreditRule.SCORE_MAX, CreditLevel.EXCELLENT.getMaxScore());
        // 初始分必须落在 GOOD 区间内（等级表与初始分不能各说各话）
        assertEquals(CreditLevel.GOOD, CreditLevel.fromScore(CreditRule.SCORE_DEFAULT));

        // 区间连续且无重叠：整个 [SCORE_MIN, SCORE_MAX] 恰被覆盖一次
        for (int score = CreditRule.SCORE_MIN; score <= CreditRule.SCORE_MAX; score++) {
            int matched = 0;
            for (CreditLevel level : CreditLevel.values()) {
                if (level.contains(score)) {
                    matched++;
                }
            }
            assertEquals(1, matched, "分数 " + score + " 必须恰好落在一个等级区间内");
        }

        assertEquals(CreditLevel.POOR, CreditLevel.fromScore(0));
        assertEquals(CreditLevel.POOR, CreditLevel.fromScore(79));
        assertEquals(CreditLevel.FAIR, CreditLevel.fromScore(80));
        assertEquals(CreditLevel.FAIR, CreditLevel.fromScore(99));
        assertEquals(CreditLevel.GOOD, CreditLevel.fromScore(100));
        assertEquals(CreditLevel.GOOD, CreditLevel.fromScore(129));
        assertEquals(CreditLevel.EXCELLENT, CreditLevel.fromScore(130));
        assertEquals(CreditLevel.EXCELLENT, CreditLevel.fromScore(200));
        assertEquals(CreditLevel.GOOD, CreditLevel.fromScore(null));
        assertEquals(CreditLevel.POOR, CreditLevel.fromScore(-10));
        assertEquals(CreditLevel.EXCELLENT, CreditLevel.fromScore(1000));
    }

    @Test
    @DisplayName("8. 三条信用路径走真实入口：加分 → 冲正 → 恢复 后净漂移为 0")
    void test08_creditThreePathsRoundTripHasZeroDrift() {
        Long goodsId = createGoods(GoodsStatus.SOLD);
        TradeOrder order = createOrder(goodsId, OrderStatus.COMPLETED);
        Review review = createReview(order.getId(), goodsId, 5);

        resetCredit(SELLER_ID);
        creditService.getOrCreateCredit(SELLER_ID);
        assertEquals(CreditRule.SCORE_DEFAULT, creditScoreOf(SELLER_ID));

        // 路径 1：评价创建 → 信用加分（真实监听器入口）
        creditReviewEventListener.handleReviewCreated(ReviewCreatedEvent.builder()
                .reviewId(review.getId())
                .orderId(order.getId())
                .goodsId(goodsId)
                .reviewerId(BUYER_ID)
                .reviewedUserId(SELLER_ID)
                .score(5)
                .build());
        int afterCreate = creditScoreOf(SELLER_ID);
        assertEquals(CreditRule.SCORE_DEFAULT + CreditRule.reviewDeltaForScore(5), afterCreate,
                "创建阶段的信用变动必须等于 CreditRule 的星级分值");

        // 路径 2：管理员屏蔽 → 精准冲正（真实治理入口，含审计日志与幂等键）
        handleShieldReport(review.getId(), THIRD_PARTY_ID, "5星好评违规，屏蔽冲正");
        assertEquals(ReviewStatus.AUDIT_REJECTED, reviewMapper.selectById(review.getId()).getStatus());
        int afterReversal = creditScoreOf(SELLER_ID);
        assertEquals(CreditRule.SCORE_DEFAULT, afterReversal,
                "创建 + 冲正 必须回到初始分（被屏蔽的评价不再贡献分数）");

        // 路径 3：管理员恢复展示 → 补偿（新审计 ID = 新 actionKey，必须再次生效）
        adminGovernanceService.restoreReview(ADMIN_ID, ADMIN_USERNAME, review.getId(), "复核后恢复展示", "127.0.0.1");
        assertEquals(ReviewStatus.VISIBLE, reviewMapper.selectById(review.getId()).getStatus());
        int afterRestore = creditScoreOf(SELLER_ID);
        assertEquals(afterCreate, afterRestore,
                "恢复后必须回到「评价正常展示」的分数：冲正 + 恢复 净漂移为 0");

        // 对账恒等式仍然成立（change_score 记录实际生效值）
        assertEquals(CreditRule.SCORE_DEFAULT + ledgerSumOf(SELLER_ID), afterRestore,
                "主档余额必须等于 初始分 + 流水实际变动之和");
    }

    // =========================================================================
    // 4. 输入保真：不做黑名单改写
    // =========================================================================

    @Test
    @DisplayName("9. 评价正文保真存储：<b>、javascript、&quot; 一律不得被改写")
    void test09_reviewContentIsStoredVerbatim() {
        Long goodsId = createGoods(GoodsStatus.SOLD);
        TradeOrder order = createOrder(goodsId, OrderStatus.COMPLETED);

        String raw = "<b>加粗</b> javascript &quot;引号&quot; <script>alert(1)</script> 5<10";
        CreateReviewRequest request = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content(raw)
                .tags(List.of("  <b>标签</b>  ", "javascript"))
                .build();

        reviewService.createReview(BUYER_ID, request);

        Review stored = reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, order.getId())
                .eq(Review::getReviewerId, BUYER_ID));
        assertNotNull(stored);
        assertEquals(raw, stored.getContent(),
                "正文必须按原文存储：<b> 不得被删除、javascript 不得被截成 java、&quot; 不得被改写");
        assertTrue(stored.getTags().contains("<b>标签</b>"), "标签同样保真（仅去首尾空白）");
        assertTrue(stored.getTags().contains("javascript"));

        // 输出侧转义工具：显式调用时才是转义点（本项目 Flutter 端按纯文本渲染，不需要在 JSON 上做有损转义）
        assertEquals("&lt;b&gt;x&lt;/b&gt;", HtmlEscapeUtils.escape("<b>x</b>"));
        assertEquals("&amp;lt;", HtmlEscapeUtils.escape("&lt;"));
        assertEquals("a &quot;b&quot; &#39;c&#39;", HtmlEscapeUtils.escape("a \"b\" 'c'"));
        assertNull(HtmlEscapeUtils.escape(null));
        assertTrue(HtmlEscapeUtils.containsHtmlMarkup(raw));
        assertTrue(HtmlEscapeUtils.containsHtmlMarkup("</b>"));
        assertFalse(HtmlEscapeUtils.containsHtmlMarkup("javascript"));
        assertFalse(HtmlEscapeUtils.containsHtmlMarkup("&quot;引号&quot;"));
        assertFalse(HtmlEscapeUtils.containsHtmlMarkup("价格 <100"), "单独的 < 不是标记");
        assertFalse(HtmlEscapeUtils.containsHtmlMarkup(null));
    }

    @Test
    @DisplayName("10. 评价窗口按 CreditRule 判定：超过 7 天必须是 422")
    void test10_reviewWindowUsesCreditRule() {
        Long goodsId = createGoods(GoodsStatus.SOLD);
        TradeOrder order = createOrder(goodsId, OrderStatus.COMPLETED);
        // 完成时间前移到窗口之外（8 天）
        order.setCompletedTime(LocalDateTime.now().minusDays(CreditRule.REVIEW_WINDOW_DAYS + 1L));
        tradeOrderMapper.updateById(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.createReview(
                BUYER_ID,
                CreateReviewRequest.builder().orderId(order.getId()).score(5).content("太晚了").build()));
        assertEquals(422, ex.getCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.httpStatus(), "422 业务码必须映射为真实 HTTP 422");
        assertTrue(ex.getMessage().contains(String.valueOf(CreditRule.REVIEW_WINDOW_DAYS)),
                "提示文案中的天数必须来自 CreditRule：" + ex.getMessage());
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private Set<String> toFieldSet(JsonNode node) {
        Set<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void initUser(Long id, String username, String nickname, String role) {
        if (userMapper.selectById(id) != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(User.builder()
                .id(id)
                .username(username)
                .password("$2a$10$abcdefghijklmnopqrstuvwxyz1234567890")
                .nickname(nickname)
                .role(role)
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build());
    }

    private Long createGoods(GoodsStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Goods goods = Goods.builder()
                .sellerId(SELLER_ID)
                .schoolId(1L)
                .categoryId(1L)
                .title("契约收敛测试商品 " + UUID.randomUUID().toString().substring(0, 8))
                .description("阶段 7-D 契约收敛回归测试商品")
                .price(new BigDecimal("88.00"))
                .originalPrice(new BigDecimal("188.00"))
                .conditionLevel("95新")
                .status(status.getCode())
                .location("图书馆一楼")
                .viewCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();
        goodsMapper.insert(goods);
        createdGoodsIds.add(goods.getId());
        return goods.getId();
    }

    private TradeOrder createOrder(Long goodsId, OrderStatus status) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = TradeOrder.builder()
                .orderNo("ORD_7D_" + UUID.randomUUID().toString().substring(0, 8))
                .buyerId(BUYER_ID)
                .sellerId(SELLER_ID)
                .goodsId(goodsId)
                .goodsTitleSnapshot("契约收敛测试商品")
                .goodsPriceSnapshot(new BigDecimal("88.00"))
                .meetLocation("图书馆一楼")
                .orderStatus(status)
                .completedTime(status == OrderStatus.COMPLETED ? now : null)
                .createdTime(now)
                .updatedTime(now)
                .build();
        tradeOrderMapper.insert(order);
        return order;
    }

    private Review createReview(Long orderId, Long goodsId, int score) {
        LocalDateTime now = LocalDateTime.now();
        Review review = Review.builder()
                .orderId(orderId)
                .goodsId(goodsId)
                .reviewerId(BUYER_ID)
                .reviewedUserId(SELLER_ID)
                .score(score)
                .content("契约收敛回归测试评价")
                .status(ReviewStatus.VISIBLE)
                .isAnonymous(false)
                .likeCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();
        reviewMapper.insert(review);
        return review;
    }

    private void handleShieldReport(Long reviewId, Long reporterId, String note) {
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType("REVIEW")
                .targetId(reviewId)
                .reasonType("MALICIOUS_REVIEW")
                .description(note)
                .status(ReportStatus.PENDING.getCode())
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        reportMapper.insert(report);
        adminGovernanceService.handleReport(ADMIN_ID, ADMIN_USERNAME, report.getId(),
                HandleReportRequest.builder().action("VALID").note(note).build(), "127.0.0.1");
    }

    private void resetCredit(Long userId) {
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));
    }

    private int creditScoreOf(Long userId) {
        UserCredit credit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));
        assertNotNull(credit, "信用档案必须存在: userId=" + userId);
        return credit.getCreditScore();
    }

    private int ledgerSumOf(Long userId) {
        return userCreditLogMapper.selectList(
                        new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId))
                .stream().mapToInt(UserCreditLog::getChangeScore).sum();
    }
}
