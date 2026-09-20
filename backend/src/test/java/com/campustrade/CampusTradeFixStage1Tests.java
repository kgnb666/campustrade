package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.security.TokenHashUtils;
import com.campustrade.service.CreditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage Fix-1: 核心安全与高并发一致性修复专项自动化测试套件
 * 重点覆盖：
 * 1. 【SEC-01】被冻结 (FROZEN) 用户持有效 JWT 访问受保护接口强制返回 403 并阻断
 * 2. 【SEC-01】正常 (ACTIVE) 用户持有效 JWT 访问正常放行 200
 * 3. 【BIZ-01】多线程高并发累加信用积分，数据库原子增减与行锁防写丢失 (No Lost Update)
 * 4. 【BIZ-01】混合高并发增扣积分与相关统计计数强一致性校验
 * 5. 【ARCH-01】/auth/refresh 刷新令牌合法性换发新 Access Token 成功
 * 6. 【ARCH-01】非法、过期或与 Redis 白名单不匹配的 Refresh Token 拒绝
 * 7. 【ARCH-01】被冻结用户调用 /auth/refresh 刷新令牌直接拒绝 403
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
public class CampusTradeFixStage1Tests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String FROZEN_TEST_USER = "fix1_frozen_user";
    private static final String REFRESH_TEST_USER = "fix1_refresh_user";
    private static final Long CONCURRENT_USER_ID = 88899001L;

    @BeforeEach
    void setUp() {
        cleanTestData();
        ensureConcurrentUser();
    }

    @AfterEach
    void tearDown() {
        cleanTestData();
    }

    /**
     * 并发信用用例直接以固定 ID 调用信用服务，V12 起 {@code user_credit.user_id} 有外键
     * （NOT VALID 只豁免历史行，新行照样校验），因此被引用用户必须真实存在。
     */
    private void ensureConcurrentUser() {
        if (userMapper.selectById(CONCURRENT_USER_ID) != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(User.builder()
                .id(CONCURRENT_USER_ID)
                .username("fix1_concurrent_user")
                .password(UUID.randomUUID().toString())
                .nickname("并发信用测试用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build());
    }

    private void cleanTestData() {
        // 先删子表再删父表：V12 起 user_credit / user_credit_log 对 user 有外键，
        // 顺序颠倒会因"被引用行仍然存在"而删不掉用户（NOT VALID 不豁免新增行）。
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, CONCURRENT_USER_ID));
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, CONCURRENT_USER_ID));
        userMapper.delete(new LambdaQueryWrapper<User>().in(User::getUsername, FROZEN_TEST_USER, REFRESH_TEST_USER, "fix1_concurrent_user"));
        stringRedisTemplate.delete("auth:refresh:" + CONCURRENT_USER_ID);
    }

    /**
     * 测试用例 1: 【SEC-01】被冻结用户携带有效未过期 JWT 请求受保护接口，必须被拦截并返回 403
     */
    @Test
    void test01_frozen_user_with_valid_jwt_blocked_403() throws Exception {
        // 1. 创建初始为 ACTIVE 的用户
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(FROZEN_TEST_USER)
                .password(UUID.randomUUID().toString())
                .nickname("待冻结测试用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(user);

        // 2. 签发合法的 Access Token
        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());

        // 3. 正常状态下访问受保护接口 (获取个人资料)，应成功 200
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 管理员将该用户状态置为 FROZEN 冻结
        user.setStatus("FROZEN");
        user.setUpdatedTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 5. 再次携带该有效 JWT 访问受保护接口，必须被 JwtAuthenticationFilter 物理阻断，返回 403
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账号已被冻结或禁用，请联系平台管理员"));
    }

    /**
     * 测试用例 2: 【SEC-01】ACTIVE 用户正常访问受保护接口持续放行
     */
    @Test
    void test02_active_user_allowed() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(FROZEN_TEST_USER)
                .password(UUID.randomUUID().toString())
                .nickname("正常测试用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(user);

        String token = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());

        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 测试用例 3: 【BIZ-01】多线程高并发同时增加同一用户信用积分，原子更新与行锁保证绝无写丢失 (No Lost Update)
     */
    @Test
    void test03_concurrent_credit_add_no_lost_update() throws Exception {
        // 1. 初始化用户信用档案，初始分 100
        UserCredit credit = creditService.getOrCreateCredit(CONCURRENT_USER_ID);
        assertEquals(100, credit.getCreditScore());

        int threadCount = 20;
        int scorePerThread = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // 2. 20 个并发线程同时为该用户增加积分 (每次不同 relatedId 模拟不同订单履约)
        for (int i = 0; i < threadCount; i++) {
            final long relatedId = 900000L + i;
            executor.submit(() -> {
                try {
                    creditService.addCredit(
                            CONCURRENT_USER_ID,
                            scorePerThread,
                            CreditChangeType.TRADE_COMPLETED,
                            "ORDER",
                            relatedId,
                            "并发完成订单加分测试"
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "并发加分应在 15 秒内全部执行完成");
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "20 次并发加分均应成功");

        // 3. 校验权威数据：100 + 20 * 3 = 160，绝无更新丢失
        UserCredit finalCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, CONCURRENT_USER_ID)
        );
        assertNotNull(finalCredit);
        assertEquals(160, finalCredit.getCreditScore(), "并发更新后信用分必须精确等于 160，无任何 Lost Update");
        assertEquals(20L, finalCredit.getCompletedCount(), "履约完成订单数必须精确等于 20");
        assertEquals(20, finalCredit.getTradeCount(), "累计交易次数必须精确等于 20");

        // 4. 校验流水记录也是严格 20 条
        Long logCount = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, CONCURRENT_USER_ID)
        );
        assertEquals(20L, logCount, "审计流水记录总数必须为 20");
    }

    /**
     * 测试用例 4: 【BIZ-01】混合高并发增减积分强一致性测试
     */
    @Test
    void test04_concurrent_mixed_add_and_deduct_consistency() throws Exception {
        creditService.getOrCreateCredit(CONCURRENT_USER_ID);

        int addThreads = 10;
        int deductThreads = 10;
        int totalThreads = addThreads + deductThreads;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch latch = new CountDownLatch(totalThreads);

        // 10 线程加 5 分，10 线程扣 2 分 => 最终分值 100 + 10*5 - 10*2 = 130
        for (int i = 0; i < addThreads; i++) {
            final long relatedId = 100000L + i;
            executor.submit(() -> {
                try {
                    creditService.addCredit(CONCURRENT_USER_ID, 5, CreditChangeType.TRADE_COMPLETED, "ORDER", relatedId, "加分测试");
                } finally {
                    latch.countDown();
                }
            });
        }

        for (int i = 0; i < deductThreads; i++) {
            final long relatedId = 200000L + i;
            executor.submit(() -> {
                try {
                    creditService.deductCredit(CONCURRENT_USER_ID, 2, CreditChangeType.TRADE_CANCEL_PENALTY, "ORDER", relatedId, "扣分测试");
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        executor.shutdown();

        UserCredit finalCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, CONCURRENT_USER_ID)
        );
        assertNotNull(finalCredit);
        assertEquals(130, finalCredit.getCreditScore(), "混合并发后最终分值必须严格等于 130");
        assertEquals(10L, finalCredit.getCompletedCount());
        assertEquals(10L, finalCredit.getCancelCount());
    }

    /**
     * 测试用例 5: 【ARCH-01】/auth/refresh 刷新令牌合法性测试 (成功续签 Access Token 并轮换 Refresh Token)
     */
    @Test
    void test05_refresh_token_success() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(REFRESH_TEST_USER)
                .password(UUID.randomUUID().toString())
                .nickname("刷新令牌测试用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(user);

        // 生成合法 Refresh Token 并写入 Redis 会话（Redis 中只保存 SHA-256 摘要，不存令牌明文）
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstants.JWT_REFRESH_PREFIX + user.getId(),
                TokenHashUtils.sha256Hex(refreshToken),
                7,
                TimeUnit.DAYS
        );

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        MvcResult mvcResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        // 验证返回的新 Access Token 可以成功调用受保护接口
        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(responseBody);
        String newAccessToken = root.path("data").path("accessToken").asText();
        String rotatedRefreshToken = root.path("data").path("refreshToken").asText();

        // 刷新即轮换：返回的是全新的 Refresh Token，且 Redis 中保存的是新令牌的摘要
        // 断言刻意不用 assertEquals(期望值, 实际值, ...)：失败时 JUnit 会把令牌明文打进报告，
        // 与"日志/产物中不得出现完整令牌"的约束冲突，这里只断言布尔结果。
        assertFalse(refreshToken.equals(rotatedRefreshToken), "刷新成功后必须轮换出新的 Refresh Token");
        assertTrue(TokenHashUtils.sha256Hex(rotatedRefreshToken)
                        .equals(stringRedisTemplate.opsForValue().get(RedisKeyConstants.JWT_REFRESH_PREFIX + user.getId())),
                "Redis 会话必须已更新为新 Refresh Token 的摘要");

        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(REFRESH_TEST_USER));
    }

    /**
     * 测试用例 6: 【ARCH-01】伪造或过期的 Refresh Token 被拒绝 401
     */
    @Test
    void test06_refresh_token_invalid_returns_401() throws Exception {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("invalid.fake.jwt.token.string")
                .build();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 测试用例 7: 【ARCH-01】与 Redis 白名单不匹配的 Refresh Token 被拒绝 401
     */
    @Test
    void test07_refresh_token_mismatched_redis_rejected_401() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(REFRESH_TEST_USER)
                .password(UUID.randomUUID().toString())
                .nickname("刷新白名单失配用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(user);

        // 生成了 Token 但未写入 Redis 会话 (模拟被其他设备登出或置换)
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        stringRedisTemplate.delete(RedisKeyConstants.JWT_REFRESH_PREFIX + user.getId());

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 测试用例 8: 【ARCH-01】被冻结用户调用 /auth/refresh 刷新令牌直接返回 403
     */
    @Test
    void test08_refresh_token_frozen_user_rejected_403() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .username(REFRESH_TEST_USER)
                .password(UUID.randomUUID().toString())
                .nickname("被冻结刷新用户")
                .role("USER")
                .status("FROZEN") // 账号被冻结
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(user);

        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUsername());
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstants.JWT_REFRESH_PREFIX + user.getId(),
                TokenHashUtils.sha256Hex(refreshToken),
                7,
                TimeUnit.DAYS
        );

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("用户账号不存在或已被封禁"));
    }
}
