package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.dto.order.CancelOrderRequest;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.entity.*;
import com.campustrade.mapper.*;
import com.campustrade.service.OrderService;
import com.campustrade.service.ai.DeepSeekClient;
import com.campustrade.support.TestCredentials;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.StudentVerifyStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4-C: 订单 REST API 接口层与权限控制集成测试套件
 * 严格覆盖 10 项核心要求与安全边界:
 * 1. 创建订单 API 成功
 * 2. 未登录 401 拦截
 * 3. 查询订单成功 (我的订单分页 / 订单详情)
 * 4. 第三方查看订单 403 权限拒绝
 * 5. 卖家确认订单成功
 * 6. 非卖家确认订单 403 权限拒绝
 * 7. 取消订单成功且联动恢复商品状态
 * 8. 取消原因为空失败 (400)
 * 9. 完成交易成功且商品状态变更为 SOLD、双方 trade_count 递增
 * 10. 分页参数校验极值拦截 (page < 1, size < 1, size > 100)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage4CTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private GoodsImageMapper goodsImageMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DeepSeekClient deepSeekClient;

    private static String tokenSeller;
    private static Long sellerId;
    private static String tokenBuyer;
    private static Long buyerId;
    private static String tokenThirdParty;
    private static Long thirdPartyId;

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @BeforeAll
    static void setupUsers(@Autowired MockMvc mockMvc,
                           @Autowired ObjectMapper objectMapper,
                           @Autowired UserMapper userMapper,
                           @Autowired StudentVerifyMapper studentVerifyMapper) throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // 1. 卖家 User Seller (清华大学)
        String usernameSeller = "stage4c_seller_" + runId;
        tokenSeller = registerAndLogin(mockMvc, objectMapper, userMapper, studentVerifyMapper,
                usernameSeller, usernameSeller + "@mails.tsinghua.edu.cn", 1L);
        sellerId = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameSeller)).getId();

        // 2. 买家 User Buyer (北京大学)
        String usernameBuyer = "stage4c_buyer_" + runId;
        tokenBuyer = registerAndLogin(mockMvc, objectMapper, userMapper, studentVerifyMapper,
                usernameBuyer, usernameBuyer + "@pku.edu.cn", 2L);
        buyerId = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameBuyer)).getId();

        // 3. 无关第三方 User ThirdParty (浙江大学)
        String usernameThirdParty = "stage4c_third_" + runId;
        tokenThirdParty = registerAndLogin(mockMvc, objectMapper, userMapper, studentVerifyMapper,
                usernameThirdParty, usernameThirdParty + "@zju.edu.cn", 3L);
        thirdPartyId = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameThirdParty)).getId();
    }

    private static String registerAndLogin(MockMvc mockMvc,
                                           ObjectMapper objectMapper,
                                           UserMapper userMapper,
                                           StudentVerifyMapper studentVerifyMapper,
                                           String username,
                                           String email,
                                           Long schoolId) throws Exception {
        RegisterRequestDTO reg = new RegisterRequestDTO();
        reg.setUsername(username);
        reg.setPassword(TEST_PASSWORD);
        reg.setEmail(email);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(user.getId())
                .schoolId(schoolId)
                .studentNumber("STU_" + UUID.randomUUID().toString().substring(0, 8))
                .schoolEmail(email)
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build());

        LoginRequestDTO login = new LoginRequestDTO();
        login.setUsername(username);
        login.setPassword(TEST_PASSWORD);

        MvcResult res = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("accessToken").asText();
    }

    private Long createTestGoods(Long ownerSellerId, String status, BigDecimal price) {
        Goods goods = Goods.builder()
                .sellerId(ownerSellerId)
                .schoolId(1L)
                .categoryId(1L)
                .title("Stage4-C API 测试商品 " + UUID.randomUUID().toString().substring(0, 6))
                .description("99新，无磕碰")
                .price(price != null ? price : new BigDecimal("299.00"))
                .originalPrice(new BigDecimal("599.00"))
                .conditionLevel("95新")
                .status(status != null ? status : GoodsStatus.ON_SALE.getCode())
                .location("学子食堂西门")
                .viewCount(5)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);

        GoodsImage image = GoodsImage.builder()
                .goodsId(goods.getId())
                .imageUrl("http://127.0.0.1:9000/campustrade/goods/stage4c-cover.png")
                .sort(1)
                .createdTime(LocalDateTime.now())
                .build();
        goodsImageMapper.insert(image);

        return goods.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE buyer_id IN (?, ?, ?) OR seller_id IN (?, ?, ?)",
                buyerId, sellerId, thirdPartyId, buyerId, sellerId, thirdPartyId);
        jdbcTemplate.update("DELETE FROM campus_trade.goods_image WHERE goods_id IN (SELECT id FROM campus_trade.goods WHERE seller_id IN (?, ?, ?))",
                buyerId, sellerId, thirdPartyId);
        jdbcTemplate.update("DELETE FROM campus_trade.goods WHERE seller_id IN (?, ?, ?)",
                buyerId, sellerId, thirdPartyId);
    }

    @Test
    @Order(1)
    @DisplayName("1. 创建订单 API 成功 - 校验商品锁定、快照完整性与初始状态")
    void test01_create_order_api_success() throws Exception {
        BigDecimal price = new BigDecimal("450.00");
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), price);

        CreateOrderRequest request = CreateOrderRequest.builder()
                .goodsId(goodsId)
                .meetLocation("图书馆正门大厅")
                .buyerMessage("请问下午四点有空吗？")
                .build();

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + tokenBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.WAIT_SELLER_CONFIRM.getCode()))
                .andExpect(jsonPath("$.data.statusDesc").value("待卖家确认"))
                .andExpect(jsonPath("$.data.goodsId").value(goodsId))
                .andExpect(jsonPath("$.data.goodsPriceSnapshot").value(450.00))
                .andExpect(jsonPath("$.data.goodsImageSnapshot").value("http://127.0.0.1:9000/campustrade/goods/stage4c-cover.png"))
                .andExpect(jsonPath("$.data.meetLocation").value("图书馆正门大厅"))
                .andExpect(jsonPath("$.data.buyerMessage").value("请问下午四点有空吗？"))
                .andExpect(jsonPath("$.data.buyer.id").value(buyerId))
                .andExpect(jsonPath("$.data.seller.id").value(sellerId))
                .andReturn();

        // 验证数据库中商品状态自动锁定为 LOCKED
        Goods updatedGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.LOCKED.getCode(), updatedGoods.getStatus(), "下单后商品状态必须锁定为 LOCKED");
    }

    @Test
    @Order(2)
    @DisplayName("2. 未登录访问订单接口统一返回 401")
    void test02_unauthenticated_returns_401() throws Exception {
        CreateOrderRequest req = CreateOrderRequest.builder().goodsId(100L).build();

        // POST /api/orders
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // GET /api/orders/my
        mockMvc.perform(get("/api/orders/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // GET /api/orders/1
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // PUT /api/orders/1/confirm
        mockMvc.perform(put("/api/orders/1/confirm"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // PUT /api/orders/1/cancel
        mockMvc.perform(put("/api/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // PUT /api/orders/1/complete
        mockMvc.perform(put("/api/orders/1/complete"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(3)
    @DisplayName("3. 查询订单成功 - 验证 /api/orders/my (买家/卖家视角) 与 /api/orders/{id}")
    void test03_query_orders_success() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("120.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "二餐门口", "查询测试");

        // 1. 买家查询我的订单
        mockMvc.perform(get("/api/orders/my")
                        .param("role", "BUYER")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].id").value(order.getId()))
                .andExpect(jsonPath("$.data.records[0].orderStatus").value(OrderStatus.WAIT_SELLER_CONFIRM.getCode()));

        // 2. 卖家查询我的订单
        mockMvc.perform(get("/api/orders/my")
                        .param("role", "SELLER")
                        .header("Authorization", "Bearer " + tokenSeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].id").value(order.getId()));

        // 3. 买家查看订单详情
        mockMvc.perform(get("/api/orders/" + order.getId())
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(order.getId()))
                .andExpect(jsonPath("$.data.orderNo").value(order.getOrderNo()));

        // 4. 卖家查看订单详情
        mockMvc.perform(get("/api/orders/" + order.getId())
                        .header("Authorization", "Bearer " + tokenSeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(order.getId()));
    }

    @Test
    @Order(4)
    @DisplayName("4. 第三方无关用户查看订单详情返回 403")
    void test04_third_party_view_order_returns_403() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("200.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "操场", "第三方测试");

        mockMvc.perform(get("/api/orders/" + order.getId())
                        .header("Authorization", "Bearer " + tokenThirdParty))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @Order(5)
    @DisplayName("5. 卖家确认接单成功 - 订单状态变更为 WAIT_MEET")
    void test05_seller_confirm_order_success() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("320.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "东门", "接单测试");

        mockMvc.perform(put("/api/orders/" + order.getId() + "/confirm")
                        .header("Authorization", "Bearer " + tokenSeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.WAIT_MEET.getCode()))
                .andExpect(jsonPath("$.data.statusDesc").value("待面交"))
                .andExpect(jsonPath("$.data.confirmedTime").isNotEmpty());
    }

    @Test
    @Order(6)
    @DisplayName("6. 非卖家尝试确认接单返回 403 (买家或第三方)")
    void test06_non_seller_confirm_order_returns_403() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("180.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "南门", "权限测试");

        // 1. 买家尝试 confirm
        mockMvc.perform(put("/api/orders/" + order.getId() + "/confirm")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        // 2. 第三方无关人员尝试 confirm
        mockMvc.perform(put("/api/orders/" + order.getId() + "/confirm")
                        .header("Authorization", "Bearer " + tokenThirdParty))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @Order(7)
    @DisplayName("7. 取消订单成功 - 订单变 CANCELLED，商品状态原子恢复为 ON_SALE")
    void test07_cancel_order_success() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("550.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "西门", "取消测试");

        CancelOrderRequest req = CancelOrderRequest.builder()
                .cancelReason("临时有事出差，取消面交")
                .build();

        mockMvc.perform(put("/api/orders/" + order.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.CANCELLED.getCode()))
                .andExpect(jsonPath("$.data.statusDesc").value("已取消"))
                .andExpect(jsonPath("$.data.cancelReason").value("临时有事出差，取消面交"))
                .andExpect(jsonPath("$.data.cancelledBy").value(buyerId));

        // 验证商品自动恢复为 ON_SALE
        Goods restoredGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.ON_SALE.getCode(), restoredGoods.getStatus());
    }

    @Test
    @Order(8)
    @DisplayName("8. 取消订单原因为空失败 - 返回 400 参数错误")
    void test08_cancel_order_empty_reason_fails() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("60.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "宿舍楼", "留言");

        // 1. 空字符串
        mockMvc.perform(put("/api/orders/" + order.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 2. 纯空白字符
        mockMvc.perform(put("/api/orders/" + order.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 3. 字段缺失
        mockMvc.perform(put("/api/orders/" + order.getId() + "/cancel")
                        .header("Authorization", "Bearer " + tokenBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @Order(9)
    @DisplayName("9. 完成交易成功 - 状态变 COMPLETED，商品变 SOLD，买卖双方 trade_count + 1")
    void test09_complete_trade_success() throws Exception {
        Long goodsId = createTestGoods(sellerId, GoodsStatus.ON_SALE.getCode(), new BigDecimal("800.00"));
        TradeOrder order = orderService.createOrder(buyerId, goodsId, "学子超市", "完成测试");

        // 先确认接单
        orderService.confirmOrder(order.getId(), sellerId);

        // 获取买卖双方初始 trade_count
        UserCredit buyerCreditBefore = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, buyerId)
        );
        int buyerCountBefore = buyerCreditBefore != null && buyerCreditBefore.getTradeCount() != null ? buyerCreditBefore.getTradeCount() : 0;

        UserCredit sellerCreditBefore = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)
        );
        int sellerCountBefore = sellerCreditBefore != null && sellerCreditBefore.getTradeCount() != null ? sellerCreditBefore.getTradeCount() : 0;

        // 买家确认完成
        mockMvc.perform(put("/api/orders/" + order.getId() + "/complete")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderStatus").value(OrderStatus.COMPLETED.getCode()))
                .andExpect(jsonPath("$.data.statusDesc").value("已完成"))
                .andExpect(jsonPath("$.data.completedTime").isNotEmpty());

        // 验证商品变更为 SOLD
        Goods soldGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.SOLD.getCode(), soldGoods.getStatus());

        // 验证双方信用记录
        UserCredit buyerCreditAfter = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, buyerId)
        );
        assertEquals(buyerCountBefore + 1, buyerCreditAfter.getTradeCount());

        UserCredit sellerCreditAfter = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, sellerId)
        );
        assertEquals(sellerCountBefore + 1, sellerCreditAfter.getTradeCount());
    }

    @Test
    @Order(10)
    @DisplayName("10. 我的订单分页参数限制验证 - 触发参数校验拦截 (400)")
    void test10_pagination_param_limits() throws Exception {
        // page < 1 校验失败
        mockMvc.perform(get("/api/orders/my")
                        .param("page", "0")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/orders/my")
                        .param("page", "-5")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // size < 1 校验失败
        mockMvc.perform(get("/api/orders/my")
                        .param("size", "0")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // size > 100 校验失败
        mockMvc.perform(get("/api/orders/my")
                        .param("size", "101")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 正常范围成功
        mockMvc.perform(get("/api/orders/my")
                        .param("page", "1")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + tokenBuyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
