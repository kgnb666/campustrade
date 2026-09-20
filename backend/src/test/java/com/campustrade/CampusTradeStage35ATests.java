package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.campustrade.support.TestCredentials;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.StudentVerifyStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
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
 * Stage 3.5-A: 数据库迁移与 Redis 数据一致性加固自动化测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage35ATests {

    private static final String FAVORITE_KEY_PREFIX = "goods:favorite:";

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Long testGoodsId;
    private static Long userAId;
    private static String tokenA;
    private static Long userBId;
    private static String tokenB;

    @BeforeEach
    void setupUsersAndGoods() throws Exception {
        if (testGoodsId != null) return;

        String runId = UUID.randomUUID().toString().substring(0, 8);
        String usernameA = "stage35A_" + runId;
        String usernameB = "stage35B_" + runId;

        tokenA = registerAndLogin(usernameA, TEST_PASSWORD, usernameA + "@test.edu.cn");
        User userA = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameA));
        assertNotNull(userA);
        userAId = userA.getId();

        // 为用户 A 完成校园认证
        StudentVerify verify = StudentVerify.builder()
                .userId(userAId)
                .schoolId(1L)
                .studentNumber("STU" + runId)
                .schoolEmail(usernameA + "@mails.tsinghua.edu.cn")
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build();
        studentVerifyMapper.insert(verify);

        // 创建测试商品
        Goods goods = Goods.builder()
                .sellerId(userAId)
                .schoolId(1L)
                .categoryId(101L)
                .title("Stage 3.5-A 一致性测试商品 " + runId)
                .description("用于测试 Redis 数据恢复与并发一致性")
                .price(new BigDecimal("199.00"))
                .conditionLevel("95新")
                .status(GoodsStatus.ON_SALE.getCode())
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);
        testGoodsId = goods.getId();

        // 准备用户 B
        tokenB = registerAndLogin(usernameB, TEST_PASSWORD, usernameB + "@test.edu.cn");
        User userB = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameB));
        assertNotNull(userB);
        userBId = userB.getId();
    }

    private String registerAndLogin(String username, String password, String email) throws Exception {
        RegisterRequestDTO reg = new RegisterRequestDTO();
        reg.setUsername(username);
        reg.setPassword(password);
        reg.setEmail(email);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        LoginRequestDTO login = new LoginRequestDTO();
        login.setUsername(username);
        login.setPassword(password);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return root.path("data").path("accessToken").asText();
    }

    @Test
    @Order(1)
    @DisplayName("1. 验证 Flyway 迁移历史已生效且无 schema 重复")
    void test01_FlywayMigrationHistoryValidation() {
        // 验证 flyway_schema_history 表中已存在并且版本全部成功
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM campus_trade.flyway_schema_history ORDER BY installed_rank"
        );
        assertNotNull(rows, "Flyway 历史记录表应存在");
        assertFalse(rows.isEmpty(), "Flyway 应至少包含迁移记录");

        System.out.println("=== Flyway Migrations Applied ===");
        for (Map<String, Object> r : rows) {
            System.out.printf("Version: %s, Description: %s, Success: %s%n",
                    r.get("version"), r.get("description"), r.get("success"));
            assertTrue(Boolean.TRUE.equals(r.get("success")) || "true".equalsIgnoreCase(String.valueOf(r.get("success"))),
                    "Migration " + r.get("version") + " 必须执行成功");
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. 验证 DB 事实源与 Redis 正常收藏及取消收藏计数一致性")
    void test02_AddAndRemoveFavorite_DbAndRedisConsistency() throws Exception {
        // 清理当前商品的测试收藏与缓存
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        redisTemplate.delete(FAVORITE_KEY_PREFIX + testGoodsId);

        // 用户 A 收藏
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // DB 必须为 1
        Long dbCount = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(1L, dbCount);

        // Redis 必须为 1
        long redisCount = favoriteService.getFavoriteCount(testGoodsId);
        assertEquals(1L, redisCount);

        // 用户 A 取消收藏
        mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // DB 必须为 0
        dbCount = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(0L, dbCount);

        // Redis 必须为 0
        redisCount = favoriteService.getFavoriteCount(testGoodsId);
        assertEquals(0L, redisCount);
    }

    @Test
    @Order(3)
    @DisplayName("3. 验证重复收藏被唯一索引及防重检查拦截且 Redis 计数不飘增")
    void test03_DuplicateFavorite_PreventedWithoutRedisDrift() throws Exception {
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        redisTemplate.delete(FAVORITE_KEY_PREFIX + testGoodsId);

        // 第一次收藏 -> 成功
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次重复收藏 -> 必须被拦截并返回 400
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("您已收藏过该商品"));

        // DB 事实源仍必须只有 1 条记录
        Long dbCount = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(1L, dbCount);

        // Redis 计数不能飘增为 2，必须等于 1
        long redisCount = favoriteService.getFavoriteCount(testGoodsId);
        assertEquals(1L, redisCount);
    }

    @Test
    @Order(4)
    @DisplayName("4. 验证 Redis 缓存缺失时 getFavoriteCount 自动从 DB 恢复并回填")
    void test04_RedisKeyAbsent_GetFavoriteCountRecoversFromDb() {
        // 显式保证 DB 中有 1 条收藏记录
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        favoriteMapper.insert(Favorite.builder().userId(userAId).goodsId(testGoodsId).createdTime(LocalDateTime.now()).build());
        Long dbCount = 1L;

        // 模拟 Redis 发生缓存淘汰 / 重启 / Flush：删除 Key
        String redisKey = FAVORITE_KEY_PREFIX + testGoodsId;
        redisTemplate.delete(redisKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)), "Redis Key 应已被删除");

        // 调用 getFavoriteCount，绝不能直接返回 0，必须从 DB 恢复真实值
        long recovered = favoriteService.getFavoriteCount(testGoodsId);
        assertEquals(dbCount.longValue(), recovered, "当 Redis key 丢失时，必须从 DB 恢复真实数据");

        // 验证 Redis 已被正确自愈回填
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)), "Redis Key 应已完成自愈回填");
        Object cached = redisTemplate.opsForValue().get(redisKey);
        assertNotNull(cached);
        assertEquals(dbCount.longValue(), Long.parseLong(cached.toString()));
    }

    @Test
    @Order(5)
    @DisplayName("5. 验证 Redis 缓存缺失时执行收藏，不会脏写入 1 而是准确从 DB 同步")
    void test05_RedisKeyAbsent_AddFavoriteRecoversFromDb() throws Exception {
        // 先确保用户 A 已收藏，用户 B 未收藏
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        favoriteMapper.insert(Favorite.builder().userId(userAId).goodsId(testGoodsId).createdTime(LocalDateTime.now()).build());

        // 此时 DB 收藏数为 1。故意清除 Redis 缓存！
        String redisKey = FAVORITE_KEY_PREFIX + testGoodsId;
        redisTemplate.delete(redisKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)));

        // 用户 B 此时执行收藏
        mockMvc.perform(post("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // DB 此时应该为 2 (用户 A + 用户 B)
        Long dbCount = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(2L, dbCount);

        // Redis 不能是单纯 INCR 产生的 1，必须是准确同步 DB 的 2！
        Object cached = redisTemplate.opsForValue().get(redisKey);
        assertNotNull(cached);
        assertEquals(2L, Long.parseLong(cached.toString()), "Redis Key 缺失时新收藏必须准确回填 DB 真实总数 2，而不是错误的 1");
    }

    @Test
    @Order(6)
    @DisplayName("6. 验证 Redis 缓存缺失时执行取消收藏，不会脏写入 -1 或 0 而是同步 DB 真实剩余数")
    void test06_RedisKeyAbsent_RemoveFavoriteRecoversFromDb() throws Exception {
        // 显式准备 用户 A 和 用户 B 两个收藏（共 2 个）
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        favoriteMapper.insert(Favorite.builder().userId(userAId).goodsId(testGoodsId).createdTime(LocalDateTime.now()).build());
        favoriteMapper.insert(Favorite.builder().userId(userBId).goodsId(testGoodsId).createdTime(LocalDateTime.now()).build());

        // 故意清除 Redis 缓存模拟缓存失效
        String redisKey = FAVORITE_KEY_PREFIX + testGoodsId;
        redisTemplate.delete(redisKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)));

        // 用户 A 取消收藏
        mockMvc.perform(delete("/favorite/" + testGoodsId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // DB 此时剩余 1 条 (用户 B)
        Long dbCountAfter = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        assertEquals(1L, dbCountAfter);

        // Redis 绝不能是 -1 或 0，必须准确同步为剩余的 1！
        Object cached = redisTemplate.opsForValue().get(redisKey);
        assertNotNull(cached);
        assertEquals(1L, Long.parseLong(cached.toString()), "Redis Key 缺失时取消收藏必须准确回填 DB 真实剩余数 1，而不是 -1 或 0");
    }

    @Test
    @Order(7)
    @DisplayName("7. 验证 Redis 计数下限保底不能低于 0 且脏数据自愈")
    void test07_RedisCountCannotBeNegative_AndCorruptedDataRecovers() {
        String redisKey = FAVORITE_KEY_PREFIX + testGoodsId;

        // 场景 A: 人为将 Redis 脏写入负数
        redisTemplate.opsForValue().set(redisKey, -99);
        // getFavoriteCount 探测到负数脏数据，必须自动从 DB 重新计算覆盖
        long count = favoriteService.getFavoriteCount(testGoodsId);
        assertTrue(count >= 0, "收藏计数绝对不能返回负数");
        Object fixedVal = redisTemplate.opsForValue().get(redisKey);
        assertTrue(Long.parseLong(fixedVal.toString()) >= 0, "Redis 计数必须已自愈为非负数");

        // 场景 B: 人为将 Redis 脏写入非数字垃圾数据
        redisTemplate.opsForValue().set(redisKey, "CORRUPTED_STRING_VALUE");
        long recovered = favoriteService.getFavoriteCount(testGoodsId);
        assertTrue(recovered >= 0, "遇到非数字数据时必须自愈恢复为 DB 真实值");
    }

    @Test
    @Order(8)
    @DisplayName("8. 验证用户隔离权限：用户 A 与用户 B 状态互不干扰")
    void test08_UserIsolation_UserBDoesNotAffectUserA() throws Exception {
        // 清空
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        redisTemplate.delete(FAVORITE_KEY_PREFIX + testGoodsId);

        // 用户 A 收藏
        mockMvc.perform(post("/favorite/" + testGoodsId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // 校验状态：A 是 true，B 是 false
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));

        // 用户 B 收藏
        mockMvc.perform(post("/favorite/" + testGoodsId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        // 校验状态：A 是 true，B 是 true，总数是 2
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        assertEquals(2L, favoriteService.getFavoriteCount(testGoodsId));

        // 用户 B 取消收藏
        mockMvc.perform(delete("/favorite/" + testGoodsId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        // 校验状态：A 仍是 true，B 变为 false，总数变为 1
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/favorite/check/" + testGoodsId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
        assertEquals(1L, favoriteService.getFavoriteCount(testGoodsId));
    }

    @Test
    @Order(9)
    @DisplayName("9. 验证同一用户高并发重复点击收藏：数据库唯一约束生效，最终仅 1 条，Redis 计数严格为 1")
    void test09_ConcurrentFavorite_SameUserSameGoods() throws Exception {
        // 清理当前商品收藏
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>().eq(Favorite::getGoodsId, testGoodsId));
        redisTemplate.delete(FAVORITE_KEY_PREFIX + testGoodsId);

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrency);

        AtomicInteger success200 = new AtomicInteger(0);
        AtomicInteger rejected400 = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/favorite/" + testGoodsId)
                                    .header("Authorization", "Bearer " + tokenA))
                            .andReturn();
                    // 业务错误现在以真实 HTTP 状态返回（业务码 == HTTP 状态码），
                    // 因此"被业务拦截"必须同时满足 HTTP 400 与 body.code == 400。
                    int httpStatus = res.getResponse().getStatus();
                    JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
                    int code = node.path("code").asInt();
                    if (httpStatus == 200 && code == 200) {
                        success200.incrementAndGet();
                    } else if (httpStatus == 400 && code == 400) {
                        rejected400.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时放行所有线程进行并发请求
        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "并发请求应在 10 秒内全部完成");
        System.out.printf("并发结果统计: 成功 200: %d, 业务拦截 400: %d, 其他异常: %d%n",
                success200.get(), rejected400.get(), otherErrors.get());

        // 验证：必须有且仅有 1 次成功
        assertEquals(1, success200.get(), "高并发下同一用户同一商品必须有且仅有 1 次成功添加");
        // 其余请求必须全部返回 400
        assertEquals(concurrency - 1, rejected400.get(), "其余并发请求必须全部被业务/唯一约束拦截并返回 400");
        assertEquals(0, otherErrors.get(), "不应产生任何未捕获的 500 或其他系统异常");

        // 最终 DB 校验：严格只有 1 条记录
        Long dbCount = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getGoodsId, testGoodsId)
                .eq(Favorite::getUserId, userAId));
        assertEquals(1L, dbCount, "数据库最终有且仅有 1 条记录");

        // 最终 Redis 计数：严格等于 1，绝无漂移
        long finalRedisCount = favoriteService.getFavoriteCount(testGoodsId);
        assertEquals(1L, finalRedisCount, "Redis 收藏计数必须严格等于 1");
    }
}
