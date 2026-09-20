package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.FavoriteService;
import com.campustrade.service.GoodsService;
import com.campustrade.service.ai.DeepSeekClient;
import com.campustrade.support.TestCredentials;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.StudentVerifyStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3.5-C: 权限隔离与异常边界测试加固
 * 覆盖六大核心维度：
 * 1. 未登录 401 拦截
 * 2. 跨用户数据隔离 (Favorite, History, Search History)
 * 3. 商品资源不存在与非法边界 (404 / 400, 零 500)
 * 4. 分页边界防御 (0, -1, 极值收敛 <= 100)
 * 5. 搜索边界与 SQL 安全 (空串, 超长, 特殊字符)
 * 6. Favorite 并发防重与取消幂等
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage35CTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private DeepSeekClient deepSeekClient;

    private static String tokenUserA;
    private static Long userAId;
    private static String tokenUserB;
    private static Long userBId;
    private static Long testGoodsId;
    private static Long offShelfGoodsId;

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @BeforeAll
    static void setupUsersAndGoods(@Autowired MockMvc mockMvc,
                                  @Autowired ObjectMapper objectMapper,
                                  @Autowired UserMapper userMapper,
                                  @Autowired StudentVerifyMapper studentVerifyMapper,
                                  @Autowired GoodsMapper goodsMapper) throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // 1. 注册并登录 User A (清华大学)
        String usernameA = "stage35C_A_" + runId;
        RegisterRequestDTO regA = new RegisterRequestDTO();
        regA.setUsername(usernameA);
        regA.setPassword(TEST_PASSWORD);
        regA.setEmail(usernameA + "@mails.tsinghua.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regA)))
                .andExpect(status().isOk());

        User userA = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameA));
        userAId = userA.getId();

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(userAId)
                .schoolId(1L)
                .studentNumber("STU_35C_A_" + runId)
                .schoolEmail(userA.getEmail())
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build());

        LoginRequestDTO loginA = new LoginRequestDTO();
        loginA.setUsername(usernameA);
        loginA.setPassword(TEST_PASSWORD);

        MvcResult resA = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginA)))
                .andExpect(status().isOk())
                .andReturn();
        tokenUserA = objectMapper.readTree(resA.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("accessToken").asText();

        // 2. 注册并登录 User B (北京大学)
        String usernameB = "stage35C_B_" + runId;
        RegisterRequestDTO regB = new RegisterRequestDTO();
        regB.setUsername(usernameB);
        regB.setPassword(TEST_PASSWORD);
        regB.setEmail(usernameB + "@pku.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regB)))
                .andExpect(status().isOk());

        User userB = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameB));
        userBId = userB.getId();

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(userBId)
                .schoolId(2L)
                .studentNumber("STU_35C_B_" + runId)
                .schoolEmail(userB.getEmail())
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build());

        LoginRequestDTO loginB = new LoginRequestDTO();
        loginB.setUsername(usernameB);
        loginB.setPassword(TEST_PASSWORD);

        MvcResult resB = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginB)))
                .andExpect(status().isOk())
                .andReturn();
        tokenUserB = objectMapper.readTree(resB.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("accessToken").asText();

        // 3. 创建在售测试商品 (发布者 User A)
        Goods onSaleGoods = Goods.builder()
                .sellerId(userAId)
                .schoolId(1L)
                .categoryId(101L)
                .title("Stage 3.5-C 在售商品 " + runId)
                .description("用于边界与隔离测试")
                .price(new BigDecimal("99.00"))
                .originalPrice(new BigDecimal("199.00"))
                .conditionLevel("95新")
                .status(GoodsStatus.ON_SALE.getCode())
                .location("紫荆宿舍区")
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(onSaleGoods);
        testGoodsId = onSaleGoods.getId();

        // 4. 创建已逻辑删除/下架的测试商品
        Goods offShelfGoods = Goods.builder()
                .sellerId(userAId)
                .schoolId(1L)
                .categoryId(101L)
                .title("Stage 3.5-C 已下架商品 " + runId)
                .description("已下架不应允许收藏")
                .price(new BigDecimal("50.00"))
                .conditionLevel("9成新")
                .status(GoodsStatus.OFF_SHELF.getCode())
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(offShelfGoods);
        offShelfGoodsId = offShelfGoods.getId();
    }

    // =========================================================================
    // 一、未登录 401 拦截测试 (全部需要认证的接口)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 未登录 - POST /api/favorite/{goodsId} 返回 401")
    void test01_unauthenticated_favorite_post() throws Exception {
        mockMvc.perform(post("/favorite/" + testGoodsId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(2)
    @DisplayName("2. 未登录 - DELETE /api/favorite/{goodsId} 返回 401")
    void test02_unauthenticated_favorite_delete() throws Exception {
        mockMvc.perform(delete("/favorite/" + testGoodsId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(3)
    @DisplayName("3. 未登录 - GET /api/favorite/check/{goodsId} 返回 401")
    void test03_unauthenticated_favorite_check() throws Exception {
        mockMvc.perform(get("/favorite/check/" + testGoodsId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(4)
    @DisplayName("4. 未登录 - GET /api/favorite/list 返回 401")
    void test04_unauthenticated_favorite_list() throws Exception {
        mockMvc.perform(get("/favorite/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(5)
    @DisplayName("5. 未登录 - GET /api/history/list 返回 401")
    void test05_unauthenticated_history_list() throws Exception {
        mockMvc.perform(get("/history/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(6)
    @DisplayName("6. 未登录 - GET /api/goods/search/history 返回 401")
    void test06_unauthenticated_search_history() throws Exception {
        mockMvc.perform(get("/goods/search/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(7)
    @DisplayName("7. 未登录 - POST /api/ai/goods/** 返回 401")
    void test07_unauthenticated_ai_endpoints() throws Exception {
        mockMvc.perform(post("/ai/goods/description")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"手机\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/ai/goods/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"手机\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/ai/goods/price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"手机\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(8)
    @DisplayName("8. 公开接口 - 未登录访问公开搜索与热搜成功返回 200")
    void test08_public_search_endpoints_allow_unauthenticated() throws Exception {
        mockMvc.perform(get("/goods/search").param("keyword", "手机"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/goods/search/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // =========================================================================
    // 二、跨用户数据隔离测试 (User A vs User B)
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("9. 跨用户隔离 - Favorite: A收藏后，B的check为false，B无法删除A的收藏，B列表不含A收藏")
    void test09_user_isolation_favorite() throws Exception {
        // User A 收藏商品
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // User A 检查：应为 true
        mockMvc.perform(get("/favorite/check/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // User B 检查：应为 false
        mockMvc.perform(get("/favorite/check/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        // User B 试图删除 A 的收藏：应报错（"您尚未收藏该商品"）
        mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // User A 收藏依然完好
        mockMvc.perform(get("/favorite/check/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        // User B 查看收藏列表：records 为空或不包含此商品
        MvcResult resListB = mockMvc.perform(get("/favorite/list")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recordsB = objectMapper.readTree(resListB.getResponse().getContentAsString()).path("data").path("records");
        for (JsonNode item : recordsB) {
            assertNotEquals(testGoodsId.longValue(), item.path("goodsId").asLong(), "User B 不应看到 User A 收藏的商品");
        }
    }

    @Test
    @Order(10)
    @DisplayName("10. 跨用户隔离 - History: A浏览商品，B的历史足迹中绝不包含该商品")
    void test10_user_isolation_browse_history() throws Exception {
        // User A 访问商品详情产生足迹
        mockMvc.perform(get("/goods/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // User B 查询足迹列表：不应包含 testGoodsId
        MvcResult resHistoryB = mockMvc.perform(get("/history/list")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recordsB = objectMapper.readTree(resHistoryB.getResponse().getContentAsString()).path("data").path("records");
        for (JsonNode item : recordsB) {
            assertNotEquals(testGoodsId.longValue(), item.path("goodsId").asLong(), "User B 历史足迹中不应包含 User A 浏览的商品");
        }

        // User A 查询足迹列表：应包含 testGoodsId
        MvcResult resHistoryA = mockMvc.perform(get("/history/list")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recordsA = objectMapper.readTree(resHistoryA.getResponse().getContentAsString()).path("data").path("records");
        boolean foundA = false;
        for (JsonNode item : recordsA) {
            if (item.path("goodsId").asLong() == testGoodsId.longValue()) {
                foundA = true;
                break;
            }
        }
        assertTrue(foundA, "User A 历史足迹中应准确包含自己浏览的商品");
    }

    @Test
    @Order(11)
    @DisplayName("11. 跨用户隔离 - Search History: A搜索专属词，B的搜索历史列表中绝不包含该词")
    void test11_user_isolation_search_history() throws Exception {
        String privateWordA = "UserA_search_" + UUID.randomUUID().toString().substring(0, 8);

        // User A 执行搜索
        mockMvc.perform(get("/goods/search")
                        .param("keyword", privateWordA)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // User B 查询个人搜索历史：绝不能包含 privateWordA
        MvcResult resB = mockMvc.perform(get("/goods/search/history")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listB = objectMapper.readTree(resB.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        for (JsonNode node : listB) {
            assertNotEquals(privateWordA, node.asText(), "User B 搜索历史不能泄露 User A 的搜索记录");
        }

        // User A 查询个人搜索历史：应包含 privateWordA
        MvcResult resA = mockMvc.perform(get("/goods/search/history")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listA = objectMapper.readTree(resA.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        boolean found = false;
        for (JsonNode node : listA) {
            if (privateWordA.equals(node.asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "User A 应能查到自己的个人搜索历史");
    }

    // =========================================================================
    // 三、商品资源不存在与非法边界 (404 / 400, 零 500)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("12. 商品边界 - 不存在的 goodsId (9999999) 友好返回 404，无 500")
    void test12_goods_not_found_boundary() throws Exception {
        // GET /goods/{id} 是公开接口（SecurityConfig: /goods/{id:[0-9]+} permitAll）
        mockMvc.perform(get("/goods/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        // POST /favorite/{goodsId} 需要认证
        mockMvc.perform(post("/favorite/9999999")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(13)
    @DisplayName("13. 商品边界 - 已下架/已删除商品收藏拦截返回 404，无 500")
    void test13_goods_off_shelf_boundary() throws Exception {
        mockMvc.perform(post("/favorite/" + offShelfGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(14)
    @DisplayName("14. 商品边界 - 负数与 0 goodsId 安全拦截返回 404，无数据库异常")
    void test14_goods_negative_and_zero_id() throws Exception {
        // SecurityConfig: /goods/{id:[0-9]+} 只匹配正整数路径，-1 不匹配公开规则需附带认证
        mockMvc.perform(get("/goods/-1")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        // /goods/0 匹配 [0-9]+ 是公开接口，无需 token
        mockMvc.perform(get("/goods/0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/favorite/-1")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/favorite/0")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @Order(15)
    @DisplayName("15. 参数边界 - 非法路径参数类型 (/favorite/abc) 返回 400，杜绝 500")
    void test15_goods_invalid_path_variable() throws Exception {
        // '/goods/abc' 不匹配公开规则 /goods/{id:[0-9]+}，需附带认证
        // MethodArgumentTypeMismatchException 有 @ResponseStatus(BAD_REQUEST)，HTTP 状态为 400
        mockMvc.perform(get("/goods/abc")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/favorite/abc")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // =========================================================================
    // 四、分页边界测试 (0, -1, 极值收敛 <= 100)
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("16. 分页边界 - Favorite: 验证 page=1, size=1, 100(最大), 0, -1, 999999(收敛上限)")
    void test16_pagination_favorite_list_boundaries() throws Exception {
        // size = 1
        mockMvc.perform(get("/favorite/list").param("page", "1").param("size", "1")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1));

        // size = 100 (最大允许值)
        mockMvc.perform(get("/favorite/list").param("page", "1").param("size", "100")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        // size = 0 自动纠偏为 10
        mockMvc.perform(get("/favorite/list").param("page", "1").param("size", "0")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(10));

        // size = -1 自动纠偏为 10
        mockMvc.perform(get("/favorite/list").param("page", "-1").param("size", "-1")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        // size = 999999 (恶意极大值) 强制安全收敛至 100，拒绝 OOM
        mockMvc.perform(get("/favorite/list").param("page", "1").param("size", "999999")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @Order(17)
    @DisplayName("17. 分页边界 - History: 验证 size 纠偏与极大值收敛")
    void test17_pagination_history_list_boundaries() throws Exception {
        mockMvc.perform(get("/history/list").param("page", "0").param("size", "-5")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(10));

        mockMvc.perform(get("/history/list").param("page", "1").param("size", "500000")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @Order(18)
    @DisplayName("18. 分页边界 - Goods Search: 验证分页极大值收敛")
    void test18_pagination_goods_search_boundaries() throws Exception {
        mockMvc.perform(get("/goods/search").param("page", "1").param("size", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    // =========================================================================
    // 五、搜索边界测试 (空串, 超长, 特殊字符)
    // =========================================================================

    @Test
    @Order(19)
    @DisplayName("19. 搜索边界 - 空串与纯空格搜索正常响应且不产生无意义热搜")
    void test19_search_empty_and_whitespace_keyword() throws Exception {
        // 空字符串
        mockMvc.perform(get("/goods/search").param("keyword", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 纯空格
        mockMvc.perform(get("/goods/search").param("keyword", "     "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 热搜榜不应出现空字符串
        MvcResult hotRes = mockMvc.perform(get("/goods/search/hot")).andExpect(status().isOk()).andReturn();
        JsonNode hotList = objectMapper.readTree(hotRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        for (JsonNode kw : hotList) {
            assertFalse(kw.asText().trim().isEmpty(), "热搜榜单绝不包含无意义空白内容");
        }
    }

    @Test
    @Order(20)
    @DisplayName("20. 搜索边界 - 200字符超长 keyword 安全截断执行，无 SQL 或溢出报错")
    void test20_search_super_long_keyword() throws Exception {
        String longKeyword = "二手考研复习数学历年真题详解超长测试关键词".repeat(10); // 200+ 字符

        mockMvc.perform(get("/goods/search")
                        .param("keyword", longKeyword)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @Order(21)
    @DisplayName("21. 搜索边界 - 特殊字符注入测试，SQL 参数化免疫防线，无异常崩溃")
    void test21_search_special_characters_sql_injection() throws Exception {
        // SQL 注入常见载荷
        String[] payloads = {
                "' OR '1'='1",
                "'; DROP TABLE campus_trade.goods; --",
                "%%%' AND 1=1 --",
                "<script>alert('xss')</script>",
                "🚴‍♂️🚲 混合Emoji与多语言测试 100% #!@$^*()",
                "English 123 中文混合搜索"
        };

        for (String payload : payloads) {
            mockMvc.perform(get("/goods/search").param("keyword", payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    // =========================================================================
    // 六、Favorite 并发防重与取消幂等
    // =========================================================================

    @Test
    @Order(22)
    @DisplayName("22. 并发测试 - 同一用户并发 10 线程同时收藏同一商品：DB 只有 1 条且 Redis 不飘增")
    void test22_favorite_concurrency_duplicate_requests() throws Exception {
        // 创建一个全新的测试商品专门用于此并发测试
        Goods concurrentGoods = Goods.builder()
                .sellerId(userAId)
                .schoolId(1L)
                .categoryId(101L)
                .title("并发专用测试商品")
                .price(new BigDecimal("123.00"))
                .conditionLevel("全新")
                .status(GoodsStatus.ON_SALE.getCode())
                .createdTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(concurrentGoods);
        Long targetGoodsId = concurrentGoods.getId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/favorite/" + targetGoodsId)
                                    .header("Authorization", "Bearer " + tokenUserB))
                            .andReturn();
                    // 必须解析 JSON body 中的 code 区分成功/失败
                    String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    JsonNode json = objectMapper.readTree(responseBody);
                    int code = json.path("code").asInt();
                    if (code == 200) {
                        successCount.incrementAndGet();
                    } else if (code == 400) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 准时并发释放
        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "并发任务应在10秒内完成");
        executor.shutdown();

        // 验证：成功且仅成功 1 次，其余并发全部被拦截返回友好 400
        assertEquals(1, successCount.get(), "并发重复收藏只能有 1 次成功");
        assertEquals(threadCount - 1, conflictCount.get(), "其余重复请求必须全部拦截为 400");

        // 验证：数据库中该用户对该商品记录严格只有 1 条
        Long dbCount = favoriteMapper.selectCount(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userBId)
                        .eq(Favorite::getGoodsId, targetGoodsId)
        );
        assertEquals(1L, dbCount, "数据库中收藏记录必须唯有一条");

        // 验证：Redis 收藏计数准确为 1，绝不因并发冲突飘增
        long redisCount = favoriteService.getFavoriteCount(targetGoodsId);
        assertEquals(1L, redisCount, "Redis 收藏计数器必须为 1");
    }

    @Test
    @Order(23)
    @DisplayName("23. 幂等与保底 - 取消不存在的收藏返回 400，Redis 计数绝不低于 0，无 500")
    void test23_remove_favorite_idempotent_and_non_negative() throws Exception {
        // User B 对未收藏的 testGoodsId 取消收藏
        mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("您尚未收藏该商品"));

        // 再次重复调用取消：同样幂等返回 400
        mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        // 验证 Redis 计数保底不能为负数
        long count = favoriteService.getFavoriteCount(testGoodsId);
        assertTrue(count >= 0, "Redis 收藏数保底必须 >= 0");
    }
}
