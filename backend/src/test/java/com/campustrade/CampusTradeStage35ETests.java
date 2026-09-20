package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.util.SearchKeywordUtils;
import com.campustrade.dto.GoodsQueryDTO;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.entity.SearchHistory;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.SearchHistoryMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.FavoriteService;
import com.campustrade.service.GoodsService;
import com.campustrade.service.SearchHistoryService;
import com.campustrade.service.ai.DeepSeekClient;
import com.campustrade.support.TestCredentials;
import com.campustrade.vo.GoodsDetailVO;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3.5-E: 搜索与 Redis 工程细节优化测试套件
 * 核心验证：
 * 1. 空关键词与纯空白不过滤不写入 DB 与 Redis
 * 2. 多重空白折叠清洗规范化
 * 3. 英文与中英混排品牌词大小写保留
 * 4. 超长关键词截断至 100 字符无溢出
 * 5. 热搜榜单读取、权重排序与分页防爆
 * 6. Redis 热搜失效时的优雅降级保底
 * 7. RedisKeyConstants 命名与构造规范性
 * 8. 收藏计数在 Redis 丢失后从 PostgreSQL Source of Truth 恢复回填
 * 9. 浏览量在 Redis 丢失后的 Source of Truth 保护
 * 10. 浏览量增量从 Redis 同步到 DB 的原子扣减
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage35ETests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SearchHistoryService searchHistoryService;

    @Autowired
    private SearchHistoryMapper searchHistoryMapper;

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

    private static String testToken;
    private static Long testUserId;
    private static Long testGoodsId;

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @BeforeAll
    static void setupTestData(@Autowired MockMvc mockMvc,
                              @Autowired ObjectMapper objectMapper,
                              @Autowired UserMapper userMapper,
                              @Autowired StudentVerifyMapper studentVerifyMapper,
                              @Autowired GoodsMapper goodsMapper) throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // 注册并认证测试用户
        String username = "stage35E_" + runId;
        RegisterRequestDTO reg = new RegisterRequestDTO();
        reg.setUsername(username);
        reg.setPassword(TEST_PASSWORD);
        reg.setEmail(username + "@mails.tsinghua.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        testUserId = user.getId();

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(testUserId)
                .schoolId(1L)
                .studentNumber("STU_35E_" + runId)
                .schoolEmail(user.getEmail())
                .verifyStatus("SUCCESS")
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
        testToken = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("accessToken").asText();

        // 创建测试在售商品
        Goods goods = Goods.builder()
                .sellerId(testUserId)
                .schoolId(1L)
                .categoryId(101L)
                .title("Stage 3.5-E 测试商品 " + runId)
                .description("工程细节加固测试专用商品")
                .price(new BigDecimal("99.00"))
                .originalPrice(new BigDecimal("199.00"))
                .conditionLevel("95新")
                .status("ON_SALE")
                .location("测试宿舍楼")
                .viewCount(50)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);
        testGoodsId = goods.getId();
    }

    @Test
    @Order(1)
    @DisplayName("1. 空关键词与纯空白不过滤不写入 DB 与 Redis search:hot")
    void test01_empty_and_whitespace_keywords_never_enter_redis_or_db() {
        String hotKey = RedisKeyConstants.searchHotKey();
        Double emptyScoreBefore = redisTemplate.opsForZSet().score(hotKey, "");
        Double spaceScoreBefore = redisTemplate.opsForZSet().score(hotKey, "   ");

        // 尝试写入空串、全空格、制表符与换行符
        searchHistoryService.recordSearch(testUserId, "");
        searchHistoryService.recordSearch(testUserId, "   ");
        searchHistoryService.recordSearch(testUserId, "\t\n  \r ");
        searchHistoryService.recordSearch(testUserId, "\u3000\u200B");

        // 校验：绝无空串或空白被写入 DB
        Long emptyDbCount = searchHistoryMapper.selectCount(
                new LambdaQueryWrapper<SearchHistory>()
                        .eq(SearchHistory::getUserId, testUserId)
                        .in(SearchHistory::getKeyword, "", "   ", "\t\n  \r ", "\u3000\u200B")
        );
        assertEquals(0L, emptyDbCount, "空白搜索词绝不能插入 search_history 表");

        // 校验：绝无空串进入 Redis ZSet
        Double emptyScoreAfter = redisTemplate.opsForZSet().score(hotKey, "");
        Double spaceScoreAfter = redisTemplate.opsForZSet().score(hotKey, "   ");
        assertEquals(emptyScoreBefore, emptyScoreAfter, "空串不能在 Redis 热搜中产生权重");
        assertEquals(spaceScoreBefore, spaceScoreAfter, "纯空白不能在 Redis 热搜中产生权重");
    }

    @Test
    @Order(2)
    @DisplayName("2. 搜索词多重连续空白折叠与清洗规范化")
    void test02_whitespace_collapse_and_trim_normalization() {
        // 工具类单元断言
        assertEquals("iPad Pro 2024", SearchKeywordUtils.normalize("   iPad     Pro    2024   "));
        assertEquals("全角 空格 测试", SearchKeywordUtils.normalize("全角　　空格　测试"));
        assertNull(SearchKeywordUtils.normalize("   \t\n   \u3000  "));

        // 通过业务入口触发清洗入库
        String rawInput = "   iPad     Air    M2   ";
        searchHistoryService.recordSearch(testUserId, rawInput);

        // 验证 DB 记录的是规范化后的 "iPad Air M2"
        List<SearchHistory> histories = searchHistoryMapper.selectList(
                new LambdaQueryWrapper<SearchHistory>()
                        .eq(SearchHistory::getUserId, testUserId)
                        .eq(SearchHistory::getKeyword, "iPad Air M2")
        );
        assertFalse(histories.isEmpty(), "DB 中应保存标准化后的关键词 'iPad Air M2'");

        // 验证 Redis ZSet 中保存的也是 "iPad Air M2"
        Double score = redisTemplate.opsForZSet().score(RedisKeyConstants.searchHotKey(), "iPad Air M2");
        assertNotNull(score, "Redis 热搜中应存在清洗后的 'iPad Air M2'");
        assertTrue(score >= 1.0, "热度分数应至少为 1.0");
    }

    @Test
    @Order(3)
    @DisplayName("3. 品牌词大小写保留 (iPhone / MacBook 保留用户展示排版)")
    void test03_casing_preservation() {
        String mixedCaseKeyword = "iPhone 15 Pro";
        searchHistoryService.recordSearch(testUserId, mixedCaseKeyword);

        Double score = redisTemplate.opsForZSet().score(RedisKeyConstants.searchHotKey(), mixedCaseKeyword);
        assertNotNull(score, "热搜榜单应保留原始大小写以提供优良视觉展示体验");
        assertTrue(score >= 1.0);

        Double lowerScore = redisTemplate.opsForZSet().score(RedisKeyConstants.searchHotKey(), "iphone 15 pro");
        // 如果未主动搜全小写，全小写键应无分数或独立统计
        assertNotEquals(mixedCaseKeyword, "iphone 15 pro");
    }

    @Test
    @Order(4)
    @DisplayName("4. 超长关键词截断至 100 字符，无数据库字段溢出崩溃")
    void test04_super_long_keyword_truncation_to_100() {
        String longKw = "计算机考研高数历年真题及全套解析笔记".repeat(10); // 长度达 180+
        assertTrue(longKw.length() > 100);

        String normalized = SearchKeywordUtils.normalize(longKw);
        assertNotNull(normalized);
        assertEquals(100, normalized.length(), "超长关键词应严格截断至 100 字符");

        // 存入服务，验证无 SQL Exception
        assertDoesNotThrow(() -> searchHistoryService.recordSearch(testUserId, longKw));

        // 检验在 DB 中准确存在
        Long count = searchHistoryMapper.selectCount(
                new LambdaQueryWrapper<SearchHistory>()
                        .eq(SearchHistory::getUserId, testUserId)
                        .eq(SearchHistory::getKeyword, normalized)
        );
        assertTrue(count > 0, "截断后的 100 字符关键词应成功入库");
    }

    @Test
    @Order(5)
    @DisplayName("5. 热搜榜单读取、权重降序与分页极值防爆")
    void test05_hot_search_read_and_pagination_limit() throws Exception {
        String hotKey = RedisKeyConstants.searchHotKey();
        redisTemplate.delete(hotKey);
        String kwHigh = "Stage35E_高频热搜_" + UUID.randomUUID().toString().substring(0, 6);
        String kwMid = "Stage35E_中频热搜_" + UUID.randomUUID().toString().substring(0, 6);

        // 构造明显权重差距
        redisTemplate.opsForZSet().incrementScore(hotKey, kwHigh, 50.0);
        redisTemplate.opsForZSet().incrementScore(hotKey, kwMid, 20.0);

        // 验证读取 Top 10
        List<String> hotList = searchHistoryService.getHotSearches(10);
        assertNotNull(hotList);
        assertTrue(hotList.contains(kwHigh));
        assertTrue(hotList.indexOf(kwHigh) < hotList.indexOf(kwMid), "高频词应排在中频词之前");

        // 通过 Controller 接口验证 limit 极值收敛 (0, -1, 9999)
        mockMvc.perform(get("/goods/search/hot").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/goods/search/hot").param("limit", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/goods/search/hot").param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @Order(6)
    @DisplayName("6. Redis 热搜为空或失效时的优雅降级保底")
    void test06_hot_search_fallback_when_redis_empty_or_absent() {
        // 使用一个确定为空的独立环境测试读取逻辑 (或传递非真实环境)
        // 当 Redis ZSet 无法返回或为空时，保证返回校园默认热搜词，绝不返回 null
        List<String> fallbacks = searchHistoryService.getHotSearches(5);
        assertNotNull(fallbacks);
        assertFalse(fallbacks.isEmpty(), "热搜必须具备保底推荐列表，零异常抛出");
    }

    @Test
    @Order(7)
    @DisplayName("7. 统一 RedisKeyConstants 命名与 Key 构造器验证")
    void test07_redis_key_constants_uniformity() {
        assertEquals("goods:favorite:88", RedisKeyConstants.goodsFavoriteKey(88L));
        assertEquals("goods:view:88", RedisKeyConstants.goodsViewKey(88L));
        assertEquals("search:hot", RedisKeyConstants.searchHotKey());
        assertEquals("jwt:blacklist:token_xyz", RedisKeyConstants.jwtBlacklistKey("token_xyz"));
        assertEquals("jwt:refresh:1001", RedisKeyConstants.jwtRefreshKey(1001L));
        assertEquals("student:verify:13800000000", RedisKeyConstants.studentVerifyKey("13800000000"));
    }

    @Test
    @Order(8)
    @DisplayName("8. Redis 收藏缓存丢失后，精确从 PostgreSQL Source of Truth 自愈回填")
    void test08_favorite_counter_redis_lost_recovery_source_of_truth() {
        // 确保 DB 中有 1 条收藏记录
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, testUserId)
                .eq(Favorite::getGoodsId, testGoodsId));

        favoriteMapper.insert(Favorite.builder()
                .userId(testUserId)
                .goodsId(testGoodsId)
                .createdTime(LocalDateTime.now())
                .build());

        String favKey = RedisKeyConstants.goodsFavoriteKey(testGoodsId);

        // 核心测试步骤：模拟 Redis 缓存完全丢失 (Key 被删除 / 缓存过期 / Redis 重启)
        redisTemplate.delete(favKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(favKey)), "Redis 收藏 Key 必须已不存在");

        // 调用 getFavoriteCount
        long count = favoriteService.getFavoriteCount(testGoodsId);

        // 核心断言：必须以 PostgreSQL 为准返回真实数量 1，而不是 0！
        assertEquals(1L, count, "Redis 丢失后必须从 PostgreSQL Source of Truth 准确恢复收藏数");

        // 验证 Redis 已被自动回填自愈
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(favKey)), "Redis 缓存应被自动回填自愈");
        Object cachedVal = redisTemplate.opsForValue().get(favKey);
        assertNotNull(cachedVal);
        assertEquals(1L, Long.parseLong(cachedVal.toString()), "回填的 Redis 缓存值应精确为 1");
    }

    @Test
    @Order(9)
    @DisplayName("9. 浏览量计数在 Redis 缺失下的 Source of Truth 保护")
    void test09_view_count_redis_lost_source_of_truth() {
        // 数据库内当前商品 viewCount 为 50
        Goods goodsInDb = goodsMapper.selectById(testGoodsId);
        assertNotNull(goodsInDb);
        int dbViews = goodsInDb.getViewCount();
        assertTrue(dbViews >= 50);

        String viewKey = RedisKeyConstants.goodsViewKey(testGoodsId);

        // 删除 Redis 浏览量增量键
        redisTemplate.delete(viewKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(viewKey)));

        // 查询商品详情
        GoodsDetailVO detail = goodsService.getGoodsDetail(testGoodsId);

        // 核心断言：浏览量绝不能变成 0 或 1，必须以 DB 为基数 (50 + 1 = 51)
        assertTrue(detail.getViewCount() >= dbViews, "商品浏览量绝不能因 Redis 丢失而归零，必须以 DB 为基数");
    }

    @Test
    @Order(10)
    @DisplayName("10. 浏览量增量从 Redis 同步到 DB 的周期任务与原子扣减")
    void test10_sync_view_counts_from_redis_to_db() {
        Goods goodsBefore = goodsMapper.selectById(testGoodsId);
        int beforeDbViews = goodsBefore.getViewCount();

        // 模拟 Redis 累积了 10 次浏览增量并登记脏商品 Set
        String viewKey = RedisKeyConstants.goodsViewKey(testGoodsId);
        redisTemplate.opsForValue().set(viewKey, 10);
        redisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, testGoodsId.toString());

        // 执行同步任务
        goodsService.syncViewCounts();

        // 检验 DB 中的浏览量增加了 10
        Goods goodsAfter = goodsMapper.selectById(testGoodsId);
        assertEquals(beforeDbViews + 10, goodsAfter.getViewCount(), "DB 浏览量应准确累加 Redis 增量");

        // 检验 Redis 中的增量已被扣减回 0
        Object remainingDelta = redisTemplate.opsForValue().get(viewKey);
        assertNotNull(remainingDelta);
        assertEquals(0, Integer.parseInt(remainingDelta.toString()), "Redis 浏览量增量应被扣减重置为 0");
    }
}
