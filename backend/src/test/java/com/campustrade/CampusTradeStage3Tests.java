package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.config.DeepSeekProperties;
import com.campustrade.dto.AiCategoryDTO;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.AiPriceDTO;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.BrowseHistory;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.SearchHistory;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.mapper.BrowseHistoryMapper;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.SearchHistoryMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.ai.AiGoodsService;
import com.campustrade.support.TestCredentials;
import com.campustrade.vo.AiCategoryVO;
import com.campustrade.vo.AiDescriptionVO;
import com.campustrade.vo.AiPriceVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 3: 交易互动增强 + AI商品助手自动化集成测试套件
 * 严格覆盖要求的 12 项测试指标
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage3Tests {

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
    private FavoriteMapper favoriteMapper;

    @Autowired
    private BrowseHistoryMapper browseHistoryMapper;

    @Autowired
    private SearchHistoryMapper searchHistoryMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private DeepSeekProperties deepSeekProperties;

    @Autowired
    private AiGoodsService aiGoodsService;

    // 测试静态上下文共享变量
    private static String tokenUserA;
    private static Long userAId;
    private static String usernameA;

    private static String tokenUserB;
    private static Long userBId;
    private static String usernameB;

    private static Long testGoodsId;

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @BeforeAll
    static void setUpUsersAndGoods(@Autowired MockMvc mockMvc,
                                  @Autowired ObjectMapper objectMapper,
                                  @Autowired UserMapper userMapper,
                                  @Autowired StudentVerifyMapper studentVerifyMapper) throws Exception {
        // 1. 创建 User A (认证为 清华大学 schoolId=1)
        usernameA = "stage3A_" + UUID.randomUUID().toString().substring(0, 8);
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
                .studentNumber("STU_3A_001")
                .schoolEmail(userA.getEmail())
                .verifyStatus("SUCCESS")
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
        JsonNode rootA = objectMapper.readTree(resA.getResponse().getContentAsString(StandardCharsets.UTF_8));
        tokenUserA = rootA.path("data").path("accessToken").asText();

        // 2. 创建 User B (认证为 北京大学 schoolId=2)
        usernameB = "stage3B_" + UUID.randomUUID().toString().substring(0, 8);
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
                .studentNumber("STU_3B_002")
                .schoolEmail(userB.getEmail())
                .verifyStatus("SUCCESS")
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
        JsonNode rootB = objectMapper.readTree(resB.getResponse().getContentAsString(StandardCharsets.UTF_8));
        tokenUserB = rootB.path("data").path("accessToken").asText();

        // 3. User A 发布测试商品
        CreateGoodsDTO createDTO = CreateGoodsDTO.builder()
                .title("Stage3 苹果 iPhone 15 Pro Max 256G")
                .description("自用九五新，无拆修，电池效率92%，附带原装充电器和保护壳。")
                .price(new BigDecimal("5999.00"))
                .originalPrice(new BigDecimal("8999.00"))
                .conditionLevel("95新")
                .categoryId(101L) // 手机
                .location("清华大学紫荆公寓1号楼")
                .images(List.of("http://127.0.0.1:9000/campustrade/iphone15.jpg"))
                .tags(List.of("iPhone", "手机", "九五新"))
                .build();

        MvcResult createRes = mockMvc.perform(post("/goods")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDTO)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        testGoodsId = createNode.path("data").asLong();
        assertTrue(testGoodsId > 0, "商品发布失败，未获取到有效商品ID");
    }

    // =========================================================================
    // 收藏系统测试 (1 ~ 3)
    // =========================================================================

    /**
     * 1. 收藏成功
     */
    @Test
    @Order(1)
    void test01_favorite_success() throws Exception {
        MvcResult result = mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.path("code").asInt());

        // 验证数据库有记录
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userAId)
                .eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(1, count, "收藏成功后数据库中应存在且仅存在一条记录");

        // 验证检查收藏接口返回 true
        MvcResult checkRes = mockMvc.perform(get("/favorite/check/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode checkJson = objectMapper.readTree(checkRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(checkJson.path("data").asBoolean());

        // 验证收藏列表包含该商品
        MvcResult listRes = mockMvc.perform(get("/favorite/list")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listJson = objectMapper.readTree(listRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(listJson.path("data").path("records").size() > 0);
    }

    /**
     * 2. 重复收藏失败 (400 业务拦截)
     */
    @Test
    @Order(2)
    void test02_duplicate_favorite_fail() throws Exception {
        MvcResult result = mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, json.path("code").asInt());
        assertTrue(json.path("message").asText().contains("已收藏"));
    }

    /**
     * 3. 取消收藏成功
     */
    @Test
    @Order(3)
    void test03_cancel_favorite_success() throws Exception {
        MvcResult result = mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.path("code").asInt());

        // 验证数据库记录已删除
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userAId)
                .eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(0, count, "取消收藏后数据库中应无对应记录");

        // 检查状态应变为 false
        MvcResult checkRes = mockMvc.perform(get("/favorite/check/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode checkJson = objectMapper.readTree(checkRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(checkJson.path("data").asBoolean());
    }

    // =========================================================================
    // 浏览历史测试 (4 ~ 5)
    // =========================================================================

    /**
     * 4. 浏览记录生成 (用户查看商品详情自动生成足迹)
     */
    @Test
    @Order(4)
    void test04_browse_history_created() throws Exception {
        // 先清理该用户对该商品的旧历史
        browseHistoryMapper.delete(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getUserId, userAId)
                .eq(BrowseHistory::getGoodsId, testGoodsId));

        // 登录状态查看商品详情
        MvcResult result = mockMvc.perform(get("/goods/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.path("code").asInt());

        // 验证浏览历史表中已自动写入记录
        Long count = browseHistoryMapper.selectCount(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getUserId, userAId)
                .eq(BrowseHistory::getGoodsId, testGoodsId));
        assertEquals(1, count, "查看商品详情后应自动插入浏览历史记录");

        // 验证 /history/list 接口返回记录
        MvcResult historyRes = mockMvc.perform(get("/history/list")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode historyJson = objectMapper.readTree(historyRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(historyJson.path("data").path("records").size() > 0);
    }

    /**
     * 5. 重复浏览更新时间 (不产生重复数据行)
     */
    @Test
    @Order(5)
    void test05_duplicate_browse_updates_time() throws Exception {
        BrowseHistory original = browseHistoryMapper.selectOne(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getUserId, userAId)
                .eq(BrowseHistory::getGoodsId, testGoodsId));
        assertNotNull(original);
        LocalDateTime firstBrowseTime = original.getBrowseTime();

        Thread.sleep(100);

        // 再次查看该商品详情
        mockMvc.perform(get("/goods/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // 验证记录总数仍然只有 1 行 (无重复数据)
        Long count = browseHistoryMapper.selectCount(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getUserId, userAId)
                .eq(BrowseHistory::getGoodsId, testGoodsId));
        assertEquals(1, count, "重复浏览不应产生新行，必须保持唯一性");

        // 验证时间已刷新
        BrowseHistory updated = browseHistoryMapper.selectOne(new LambdaQueryWrapper<BrowseHistory>()
                .eq(BrowseHistory::getUserId, userAId)
                .eq(BrowseHistory::getGoodsId, testGoodsId));
        assertTrue(!updated.getBrowseTime().isBefore(firstBrowseTime), "重复浏览后浏览时间应更新为最新时间");
    }

    // =========================================================================
    // 搜索增强测试 (6 ~ 7)
    // =========================================================================

    /**
     * 6. 搜索成功 (多维条件与关键词检索)
     */
    @Test
    @Order(6)
    void test06_search_success() throws Exception {
        MvcResult result = mockMvc.perform(get("/goods/search")
                        .param("keyword", "iPhone")
                        .param("categoryId", "101")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.path("code").asInt());
        assertTrue(json.path("data").path("records").size() > 0);

        // 验证热搜词排行榜接口正常响应
        MvcResult hotRes = mockMvc.perform(get("/goods/search/hot"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode hotJson = objectMapper.readTree(hotRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, hotJson.path("code").asInt());
        assertTrue(hotJson.path("data").size() > 0);
    }

    /**
     * 7. 搜索历史保存 (登录用户搜索记录入库)
     */
    @Test
    @Order(7)
    void test07_search_history_saved() throws Exception {
        String testKeyword = "MacBookProM3_" + UUID.randomUUID().toString().substring(0, 6);

        mockMvc.perform(get("/goods/search")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .param("keyword", testKeyword))
                .andExpect(status().isOk());

        // 验证 search_history 表中存在该用户的搜索记录
        Long count = searchHistoryMapper.selectCount(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userAId)
                .eq(SearchHistory::getKeyword, testKeyword));
        assertEquals(1, count, "登录用户搜索后应持久化保存搜索历史");

        // 验证从 /goods/search/history 获取最近搜索
        MvcResult historyRes = mockMvc.perform(get("/goods/search/history")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode historyJson = objectMapper.readTree(historyRes.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(historyJson.path("data").toString().contains(testKeyword));
    }

    // =========================================================================
    // AI 助手服务测试 (8 ~ 10)
    // =========================================================================

    /**
     * 8. DeepSeek 配置读取测试
     */
    @Test
    @Order(8)
    void test08_deepseek_config_read() {
        assertNotNull(deepSeekProperties, "DeepSeekProperties 应当被 Spring 容器成功装配");
        assertNotNull(deepSeekProperties.getBaseUrl(), "baseUrl 不应为空");
        assertTrue(deepSeekProperties.getBaseUrl().startsWith("http"), "baseUrl 必须为合法的 http/https 协议");
        assertEquals("deepseek-chat", deepSeekProperties.getModel(), "模型名称默认应为 deepseek-chat");
        assertTrue(deepSeekProperties.getTimeout() > 0, "超时时间必须大于0");
    }

    /**
     * 9. AI 描述接口调用成功
     */
    @Test
    @Order(9)
    void test09_ai_description_success() throws Exception {
        AiDescriptionDTO dto = AiDescriptionDTO.builder()
                .title("ThinkPad X1 Carbon 笔记本")
                .conditionLevel("95新")
                .originalDescription("自用办公本，成色极新，i7处理器，16G内存，带原装充电器。")
                .location("学子阳光餐厅")
                .build();

        MvcResult result = mockMvc.perform(post("/ai/goods/description")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, json.path("code").asInt());
        JsonNode data = json.path("data");
        assertTrue(data.has("generatedDescription"), "必须返回生成的描述文本");
        assertTrue(data.path("generatedDescription").asText().length() > 10, "生成描述不应为空且具有一定长度");
        assertTrue(data.has("tags"), "必须返回标签列表");
        assertTrue(data.has("degraded"), "必须标识是否触发降级");
    }

    /**
     * 10. AI 异常降级兜底测试 (验证外部AI异常或无KEY时，平滑降级而不报错)
     */
    @Test
    @Order(10)
    void test10_ai_exception_degradation() {
        // 测试描述生成降级
        AiDescriptionDTO descDTO = AiDescriptionDTO.builder()
                .title("捷安特公路自行车 ATX777")
                .conditionLevel("9成新")
                .originalDescription("毕业出车，刹车灵敏变速顺畅")
                .location("东操场")
                .build();

        AiDescriptionVO descVO = aiGoodsService.generateDescription(descDTO);
        assertNotNull(descVO);
        assertNotNull(descVO.getGeneratedDescription());
        assertTrue(descVO.getGeneratedDescription().contains("【成色外观】") || descVO.getGeneratedDescription().contains("捷安特"),
                "降级描述需保留用户核心信息并具备结构化版式");

        // 测试分类推荐降级
        AiCategoryDTO catDTO = AiCategoryDTO.builder()
                .title("考研数学李林复习全书")
                .description("肖秀荣1000题全新未做")
                .build();
        AiCategoryVO catVO = aiGoodsService.recommendCategory(catDTO);
        assertNotNull(catVO);
        assertNotNull(catVO.getCategoryId(), "降级分类应能匹配出合理品类");

        // 测试价格建议降级
        AiPriceDTO priceDTO = AiPriceDTO.builder()
                .title("iPad Pro 11寸 M2")
                .conditionLevel("95新")
                .originalPrice(new BigDecimal("6799.00"))
                .build();
        AiPriceVO priceVO = aiGoodsService.suggestPrice(priceDTO);
        assertNotNull(priceVO);
        assertTrue(priceVO.getSuggestedPrice().compareTo(BigDecimal.ZERO) > 0, "建议售价必须大于0");
        assertTrue(priceVO.getMaxPrice().compareTo(priceVO.getMinPrice()) >= 0, "最高建议价应大于等于最低建议价");
    }

    // =========================================================================
    // 安全与隔离性测试 (11 ~ 12)
    // =========================================================================

    /**
     * 11. 未登录无法收藏 (401 拦截)
     */
    @Test
    @Order(11)
    void test11_unauthenticated_cannot_favorite() throws Exception {
        mockMvc.perform(post("/favorite/" + testGoodsId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/favorite/list"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/history/list"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/ai/goods/description")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 12. 用户数据隔离性 (用户不能操作或查看其他用户数据)
     */
    @Test
    @Order(12)
    void test12_user_isolation() throws Exception {
        // User A 收藏商品
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk());

        // User B 检查自己的收藏列表，不应包含 User A 的收藏
        MvcResult listResB = mockMvc.perform(get("/favorite/list")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode listJsonB = objectMapper.readTree(listResB.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode recordsB = listJsonB.path("data").path("records");
        boolean containsGoodsInB = false;
        for (JsonNode node : recordsB) {
            if (node.path("goodsId").asLong() == testGoodsId) {
                containsGoodsInB = true;
                break;
            }
        }
        assertFalse(containsGoodsInB, "User B 无法在自己的收藏列表中看到 User A 的私人收藏");

        // User B 试图取消收藏该商品，应返回 400 提示未收藏 (无权越权删除)
        MvcResult deleteResB = mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();
        JsonNode deleteJsonB = objectMapper.readTree(deleteResB.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(400, deleteJsonB.path("code").asInt());
        assertTrue(deleteJsonB.path("message").asText().contains("尚未收藏"));
    }
}
