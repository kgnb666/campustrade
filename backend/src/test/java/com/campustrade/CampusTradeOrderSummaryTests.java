package com.campustrade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.User;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.OrderStatus;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.support.TestCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首页"我的待办"汇总接口 {@code GET /api/orders/summary} 的契约与口径测试。
 *
 * <h2>为什么单独有这个接口</h2>
 * "待确认 / 待面交"可以由订单状态推出，但"待评价"不能：订单是 COMPLETED 只说明交易结束，
 * 是否需要"我"评价取决于 review 表里有没有 reviewer_id = 我的记录。前端逐单判断会变成 N+1，
 * 因此后端用一条聚合 SQL 算出三个计数。
 *
 * <h2>覆盖范围</h2>
 * <ol>
 *   <li>无任何订单的新用户：三项全 0（而不是 null / 缺字段），且必须是 JSON 数字；</li>
 *   <li>未登录访问：401；</li>
 *   <li>待我确认：只有卖家视角计入，买家视角必须为 0；</li>
 *   <li>待面交：买家与卖家双方都计入；</li>
 *   <li>待评价：2 个已完成订单中只评了 1 个 → 1，两个都评完 → 0；
 *       且"待评价"只看我自己评没评（对方没评不影响我的计数）；</li>
 *   <li>数据隔离：任何用户的汇总都只包含自己的订单，看不到别人的订单。</li>
 * </ol>
 *
 * <h2>为什么不走 /auth/register + /auth/login 造账号</h2>
 * 注册接口有"单一来源 IP 每小时 20 次"的限流（`security.register.ip-limit-per-hour`），
 * 而整个测试 JVM 共用一个 Redis 容器、所有测试类的注册请求共用这一份额度。
 * 这里为了覆盖"未登录 401"等真实安全链路只需要<b>合法令牌</b>，并不需要走过登录流程，
 * 因此直接建库内用户（与注册落库的字段一致，含初始信用档案）+ 用 {@link JwtTokenProvider}
 * 签发令牌：既不打满限流额度，也不再让本类与其它测试类互相影响。
 * 订单与评价仍全部走真实接口（下单 → 卖家确认 → 完成面交 → 提交评价），断言的是真实状态跃迁。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampusTradeOrderSummaryTests {

    /** 固定主键：跨用例复用同一批账号，`ensureUser` 保证只插一次 */
    private static final Long SELLER_ID = 77880001L;
    private static final Long BUYER_ID = 77880002L;
    private static final Long OTHER_ID = 77880003L;
    private static final Long FRESH_ID = 77880004L;

    private static final String SELLER_NAME = "order_summary_seller";
    private static final String BUYER_NAME = "order_summary_buyer";
    private static final String OTHER_NAME = "order_summary_other";
    private static final String FRESH_NAME = "order_summary_fresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private GoodsImageMapper goodsImageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String tokenSeller;
    private String tokenBuyer;
    private String tokenOther;
    private String tokenFresh;

    @BeforeEach
    void setUp() {
        ensureUser(SELLER_ID, SELLER_NAME, "汇总卖家");
        ensureUser(BUYER_ID, BUYER_NAME, "汇总买家");
        ensureUser(OTHER_ID, OTHER_NAME, "汇总路人");
        ensureUser(FRESH_ID, FRESH_NAME, "汇总新用户");

        tokenSeller = jwtTokenProvider.generateAccessToken(SELLER_ID, SELLER_NAME, "USER");
        tokenBuyer = jwtTokenProvider.generateAccessToken(BUYER_ID, BUYER_NAME, "USER");
        tokenOther = jwtTokenProvider.generateAccessToken(OTHER_ID, OTHER_NAME, "USER");
        tokenFresh = jwtTokenProvider.generateAccessToken(FRESH_ID, FRESH_NAME, "USER");

        cleanOrdersAndGoods();
    }

    /**
     * 建库内用户（等价于注册落库的字段：ACTIVE / USER / 初始信用档案）。
     * 口令用运行时随机值 + BCrypt 编码，源码里不固化任何可用口令。
     */
    private void ensureUser(Long id, String username, String nickname) {
        if (userMapper.selectById(id) != null) {
            return;
        }
        String randomPassword = TestCredentials.randomPassword();
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(User.builder()
                .id(id)
                .username(username)
                .password(passwordEncoder.encode(randomPassword))
                .nickname(nickname)
                .email(username + "@example.com")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build());
        // 与注册路径一致：新用户自动获得初始信用档案（信用联动需要它存在）
        userCreditMapper.insertCreditIfAbsent(IdWorker.getId(), id);
    }

    /**
     * 每个用例都从"这批账号没有历史订单"的状态开始：否则计数会随用例执行顺序累积，
     * 断言就变成"只有在特定顺序下才成立"。
     */
    private void cleanOrdersAndGoods() {
        List<Long> ids = List.of(SELLER_ID, BUYER_ID, OTHER_ID, FRESH_ID);
        jdbcTemplate.update(
                "DELETE FROM campus_trade.review WHERE reviewer_id IN (?, ?, ?, ?) OR reviewed_user_id IN (?, ?, ?, ?)",
                ids.get(0), ids.get(1), ids.get(2), ids.get(3),
                ids.get(0), ids.get(1), ids.get(2), ids.get(3));
        jdbcTemplate.update(
                "DELETE FROM campus_trade.trade_order WHERE buyer_id IN (?, ?, ?, ?) OR seller_id IN (?, ?, ?, ?)",
                ids.get(0), ids.get(1), ids.get(2), ids.get(3),
                ids.get(0), ids.get(1), ids.get(2), ids.get(3));
        jdbcTemplate.update(
                "DELETE FROM campus_trade.goods_image WHERE goods_id IN (SELECT id FROM campus_trade.goods WHERE seller_id IN (?, ?, ?, ?))",
                ids.get(0), ids.get(1), ids.get(2), ids.get(3));
        jdbcTemplate.update(
                "DELETE FROM campus_trade.goods WHERE seller_id IN (?, ?, ?, ?)",
                ids.get(0), ids.get(1), ids.get(2), ids.get(3));
    }

    // ==========================================================================
    // 用例
    // ==========================================================================

    @Test
    @DisplayName("1. 没有任何订单的新用户：三项计数均为 0（字段必须存在且是数字，不是 null）")
    void test01_new_user_all_zero() throws Exception {
        mockMvc.perform(get("/api/orders/summary")
                        .header("Authorization", "Bearer " + tokenFresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pendingSellerConfirm").exists())
                .andExpect(jsonPath("$.data.waitMeet").exists())
                .andExpect(jsonPath("$.data.toReview").exists());

        JsonNode data = summaryOf(tokenFresh);
        assertEquals(0L, data.get("pendingSellerConfirm").asLong(), "新用户没有订单，待我确认必须为 0");
        assertEquals(0L, data.get("waitMeet").asLong(), "新用户没有订单，待面交必须为 0");
        assertEquals(0L, data.get("toReview").asLong(), "新用户没有订单，待评价必须为 0");
        // 契约：三个计数是"条数"不是 ID，必须是 JSON 数字（项目只把 Long/BigInteger 转成字符串），
        // 否则前端拿到 "0" 还要多一次解析，且与接口文档给出的 {"toReview": 0} 形状不符。
        assertTrue(data.get("pendingSellerConfirm").isNumber(), "待我确认必须是 JSON 数字而非字符串");
        assertTrue(data.get("waitMeet").isNumber(), "待面交必须是 JSON 数字而非字符串");
        assertTrue(data.get("toReview").isNumber(), "待评价必须是 JSON 数字而非字符串");
    }

    @Test
    @DisplayName("2. 未登录访问待办汇总：401（待办是属于我自己的数据，不允许匿名读取）")
    void test02_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/orders/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("3. 待我确认：卖家视角计入 1，买家视角必须为 0")
    void test03_pending_seller_confirm_only_counts_for_seller() throws Exception {
        Long goodsId = createOnSaleGoods(SELLER_ID, "待确认口径商品");
        createOrder(tokenBuyer, goodsId);

        JsonNode sellerSummary = summaryOf(tokenSeller);
        assertEquals(1L, sellerSummary.get("pendingSellerConfirm").asLong(),
                "卖家应看到 1 个待自己确认的订单");
        assertEquals(0L, sellerSummary.get("waitMeet").asLong(), "订单还没被确认，不应计入待面交");

        JsonNode buyerSummary = summaryOf(tokenBuyer);
        assertEquals(0L, buyerSummary.get("pendingSellerConfirm").asLong(),
                "买家在该状态下没有可做的动作，不得计入卖家的待办");
        assertEquals(0L, buyerSummary.get("waitMeet").asLong(), "订单还没被确认，不应计入待面交");
    }

    @Test
    @DisplayName("4. 待面交：卖家确认后，买家与卖家双方都计入 1")
    void test04_wait_meet_counts_for_both_sides() throws Exception {
        Long goodsId = createOnSaleGoods(SELLER_ID, "待面交口径商品");
        Long orderId = createOrder(tokenBuyer, goodsId);
        confirmOrder(tokenSeller, orderId);

        JsonNode sellerSummary = summaryOf(tokenSeller);
        assertEquals(1L, sellerSummary.get("waitMeet").asLong(), "卖家应看到 1 个待面交订单");
        assertEquals(0L, sellerSummary.get("pendingSellerConfirm").asLong(), "确认接单后不再属于待确认");

        JsonNode buyerSummary = summaryOf(tokenBuyer);
        assertEquals(1L, buyerSummary.get("waitMeet").asLong(),
                "卖家确认后买家也要到场面交，必须计入买家的待办");
    }

    @Test
    @DisplayName("5. 待评价：只看我自己评没评 —— 2 个已完成订单评了 1 个记 1，都评完记 0")
    void test05_to_review_counts_only_unreviewed_completed_orders() throws Exception {
        // 两个已完成的订单（同一买卖双方，两件不同商品）
        Long orderA = completeOrderBetween(tokenBuyer, tokenSeller, SELLER_ID, "待评价口径商品A");
        Long orderB = completeOrderBetween(tokenBuyer, tokenSeller, SELLER_ID, "待评价口径商品B");

        assertEquals(2L, summaryOf(tokenBuyer).get("toReview").asLong(),
                "两个已完成订单都还没评价，买家待评价应为 2");

        // 买家只评价 A
        submitReview(tokenBuyer, orderA, "买家对 A 的评价");

        assertEquals(1L, summaryOf(tokenBuyer).get("toReview").asLong(),
                "只评价了 1 个，待评价必须降到 1（剩下 B）");
        assertEquals(2L, summaryOf(tokenSeller).get("toReview").asLong(),
                "卖家一个都没评，仍应为 2：待评价只看我自己评没评，与对方评没评无关");

        // 买家补评 B → 买家清零；卖家仍未评价，不受影响
        submitReview(tokenBuyer, orderB, "买家对 B 的评价");
        assertEquals(0L, summaryOf(tokenBuyer).get("toReview").asLong(),
                "两个都评完后买家的待评价必须为 0");
        assertEquals(2L, summaryOf(tokenSeller).get("toReview").asLong(),
                "卖家仍未评价，不得因为买家评了就清零");
    }

    @Test
    @DisplayName("6. 数据隔离：谁看到的都只是自己的订单，看不到别人的")
    void test06_summary_is_isolated_per_user() throws Exception {
        // 场景：other 向 seller 下单（这条属于 seller 的"待我确认"）
        Long goodsOfSeller = createOnSaleGoods(SELLER_ID, "隔离口径商品A");
        createOrder(tokenOther, goodsOfSeller);

        assertEquals(1L, summaryOf(tokenSeller).get("pendingSellerConfirm").asLong(),
                "卖家应看到 1 条待自己确认的订单");
        JsonNode otherAsBuyer = summaryOf(tokenOther);
        assertEquals(0L, otherAsBuyer.get("pendingSellerConfirm").asLong(),
                "other 在这条订单里是买家，不能把卖家的待办算到自己头上");

        // 场景：other 也发布商品，fresh 下单（这条属于 other 的"待我确认"，与 seller 无关）
        Long goodsOfOther = createOnSaleGoods(OTHER_ID, "隔离口径商品B");
        createOrder(tokenFresh, goodsOfOther);

        assertEquals(1L, summaryOf(tokenOther).get("pendingSellerConfirm").asLong(),
                "other 现在有 1 条待自己确认的订单");
        assertEquals(1L, summaryOf(tokenSeller).get("pendingSellerConfirm").asLong(),
                "seller 仍只看到自己的那 1 条：别人的订单不得串进我的汇总");

        JsonNode freshSummary = summaryOf(tokenFresh);
        assertEquals(0L, freshSummary.get("pendingSellerConfirm").asLong(),
                "fresh 只是买家，别人的待确认订单不该出现在他的汇总里");
        assertEquals(0L, freshSummary.get("waitMeet").asLong(),
                "fresh 的订单还没被卖家确认，待面交必须为 0");
        assertEquals(0L, freshSummary.get("toReview").asLong(),
                "fresh 没有任何已完成订单，待评价必须为 0");
    }

    // ==========================================================================
    // 数据构造与请求辅助
    // ==========================================================================

    /** 取当前用户的待办汇总（断言三个计数的数值口径，避免 Integer/Long 的 JSON 数字比较歧义）。 */
    private JsonNode summaryOf(String token) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/orders/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private Long createOnSaleGoods(Long ownerId, String title) {
        Goods goods = Goods.builder()
                .sellerId(ownerId)
                .schoolId(1L)
                .categoryId(1L)
                .title(title + " " + UUID.randomUUID().toString().substring(0, 6))
                .description("待办汇总接口测试商品")
                .price(new BigDecimal("88.00"))
                .conditionLevel("9成新")
                .status(GoodsStatus.ON_SALE.getCode())
                .location("图书馆正门")
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);

        goodsImageMapper.insert(GoodsImage.builder()
                .goodsId(goods.getId())
                .imageUrl("http://127.0.0.1:9000/campustrade/goods/summary-cover.png")
                .sort(1)
                .createdTime(LocalDateTime.now())
                .build());

        return goods.getId();
    }

    private Long createOrder(String buyerToken, Long goodsId) throws Exception {
        CreateOrderRequest request = CreateOrderRequest.builder()
                .goodsId(goodsId)
                .meetLocation("图书馆正门")
                .buyerMessage("待办汇总接口测试下单")
                .build();

        MvcResult res = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.WAIT_SELLER_CONFIRM.getCode()))
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
    }

    private void confirmOrder(String sellerToken, Long orderId) throws Exception {
        mockMvc.perform(put("/api/orders/" + orderId + "/confirm")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.WAIT_MEET.getCode()));
    }

    /** 建单 → 卖家确认 → 买家完成面交，返回已完成的订单 ID。 */
    private Long completeOrderBetween(String buyerToken, String sellerToken, Long ownerId, String title) throws Exception {
        Long goodsId = createOnSaleGoods(ownerId, title);
        Long orderId = createOrder(buyerToken, goodsId);
        confirmOrder(sellerToken, orderId);

        mockMvc.perform(put("/api/orders/" + orderId + "/complete")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.COMPLETED.getCode()));

        return orderId;
    }

    private void submitReview(String reviewerToken, Long orderId, String content) throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .orderId(orderId)
                .score(5)
                .content(content)
                .tags(List.of("守时诚信"))
                .anonymous(false)
                .build();

        mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }
}
