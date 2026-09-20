package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.util.ClientIpUtils;
import com.campustrade.dto.AiDescriptionDTO;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.ReportService;
import com.campustrade.support.TestCredentials;
import com.campustrade.enums.GoodsStatus;
import com.campustrade.enums.StudentVerifyStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 终审剩余后端问题的回归测试（阶段 9 加固批次）。
 *
 * <h2>覆盖点（与交付说明逐条对应）</h2>
 * <ol>
 *   <li>可信代理解析：默认不采信转发头（伪造 XFF 不影响限流维度）、可信代理下按 XFF 解析；</li>
 *   <li>AI 接口配额：分钟频控与日配额超限返回 429，窗口恢复后可用；</li>
 *   <li>{@code /auth/refresh} 与 {@code /auth/logout} 的按 IP / 按令牌指纹限流；</li>
 *   <li>举报每日限流的原子性（并发 20 次不超发）与计数键 TTL；</li>
 *   <li>上传：对象 Content-Type 由服务端按扩展名映射；超大尺寸图片被拒；</li>
 *   <li>同一校园邮箱不能被第二个账号核销（应用层 + V12 部分唯一索引）；</li>
 *   <li>V12 迁移产物核对：部分唯一索引存在且生效、12 条外键存在且为 NOT VALID。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeFinalAuditHardenTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private ReportService reportService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.campustrade.security.JwtTokenProvider jwtTokenProvider;

    /** 与 MockMvc 配合模拟"真实 TCP 对端地址"（默认 127.0.0.1）。 */
    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // =========================================================================
    // 1. 可信代理解析（无可信代理配置 = 完全不采信转发头）
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 未配置可信代理时：伪造的 X-Forwarded-For 不影响注册/登录限流维度，计数落在 remoteAddr")
    void test01_forgedForwardedHeadersIgnoredByDefault() throws Exception {
        String forgedIp = "203.0.113.7";
        String realRemoteAddr = "10.9.9.9";

        // 清掉两个维度的计数，让断言只依赖本次请求
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(forgedIp));
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(realRemoteAddr));
        stringRedisTemplate.delete(RedisKeyConstants.loginFailIpKey(forgedIp));
        stringRedisTemplate.delete(RedisKeyConstants.loginFailIpKey(realRemoteAddr));

        // 1.1 注册：伪造 XFF + X-Real-IP，TCP 对端地址是 realRemoteAddr
        String username = "harden_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequestDTO register = new RegisterRequestDTO();
        register.setUsername(username);
        register.setPassword(TestCredentials.randomPassword());
        register.setEmail(username + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .with(remoteAddr(realRemoteAddr))
                        .header("X-Forwarded-For", forgedIp)
                        .header("X-Real-IP", forgedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        System.out.printf("[可信代理-默认] remoteAddr=%s + 伪造 XFF=%s → Redis %s=%s，%s=%s%n",
                realRemoteAddr, forgedIp,
                RedisKeyConstants.registerIpKey(realRemoteAddr),
                stringRedisTemplate.opsForValue().get(RedisKeyConstants.registerIpKey(realRemoteAddr)),
                RedisKeyConstants.registerIpKey(forgedIp),
                stringRedisTemplate.opsForValue().get(RedisKeyConstants.registerIpKey(forgedIp)));
        assertEquals("1", stringRedisTemplate.opsForValue().get(
                        RedisKeyConstants.registerIpKey(realRemoteAddr)),
                "注册限流计数必须落在 TCP 对端地址上");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstants.registerIpKey(forgedIp))),
                "伪造的 X-Forwarded-For 绝不能成为限流维度（否则换个头即可绕过注册限频）");

        // 1.2 登录：伪造 XFF，失败计数同样必须落在 remoteAddr
        LoginRequestDTO badLogin = new LoginRequestDTO();
        badLogin.setUsername(username);
        badLogin.setPassword(TestCredentials.randomPassword());

        mockMvc.perform(post("/auth/login")
                        .with(remoteAddr(realRemoteAddr))
                        .header("X-Forwarded-For", forgedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(jsonPath("$.code").value(400));

        assertEquals("1", stringRedisTemplate.opsForValue().get(
                        RedisKeyConstants.loginFailIpKey(realRemoteAddr)),
                "登录失败计数必须落在 TCP 对端地址上");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstants.loginFailIpKey(forgedIp))),
                "伪造转发头不能让失败计数落在受害者 IP 上（否则可把整段用户反向锁死）");

        // 清理：不要让这条失败记录影响其他用例
        stringRedisTemplate.delete(RedisKeyConstants.loginFailIpKey(realRemoteAddr));
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(realRemoteAddr));
    }

    @Test
    @Order(2)
    @DisplayName("2. IP 解析单元规则：CIDR 匹配、从右向左取第一个非可信地址、支持精确 IP 与端口/IPv6")
    void test02_clientIpResolutionRules() {
        // 2.1 未配置可信代理：remoteAddr 直通，XFF 永远不参与
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("127.0.0.1");
        untrusted.addHeader("X-Forwarded-For", "203.0.113.7");
        assertEquals("127.0.0.1", ClientIpUtils.resolve(untrusted, ClientIpUtils.parseTrustedProxies("")));
        assertEquals("127.0.0.1",
                ClientIpUtils.resolve(untrusted, ClientIpUtils.parseTrustedProxies(null)));

        // 2.2 remoteAddr 属于可信代理：从右向左跳过可信跳，取第一个非可信地址
        List<ClientIpUtils.IpRange> trusted = ClientIpUtils.parseTrustedProxies("127.0.0.1/32,10.0.0.0/8");
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("10.0.0.5");
        proxied.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.7");
        assertEquals("198.51.100.9", ClientIpUtils.resolve(proxied, trusted),
                "必须从右向左跳过可信代理跳，取第一个非可信地址作为真实客户端");

        // 2.3 客户端伪造最左段：伪造值只能在"更左侧"，不影响紧邻代理的那一跳
        MockHttpServletRequest spoofedLeft = new MockHttpServletRequest();
        spoofedLeft.setRemoteAddr("10.0.0.5");
        spoofedLeft.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("1.2.3.4", ClientIpUtils.resolve(spoofedLeft, trusted));

        // 2.4 整条链都是可信代理：退回最左侧一跳（而不是直接拒绝）
        MockHttpServletRequest allTrusted = new MockHttpServletRequest();
        allTrusted.setRemoteAddr("10.0.0.5");
        allTrusted.addHeader("X-Forwarded-For", "10.0.0.9, 10.0.0.7");
        assertEquals("10.0.0.9", ClientIpUtils.resolve(allTrusted, trusted));

        // 2.5 CIDR 前缀匹配：10.0.0.0/8 覆盖 10.x，不覆盖 11.x
        assertTrue(ClientIpUtils.IpRange.parse("10.0.0.0/8").contains("10.255.1.1"));
        assertFalse(ClientIpUtils.IpRange.parse("10.0.0.0/8").contains("11.0.0.1"));
        // 2.6 精确 IP 与带端口/IPv6 规范写法
        assertTrue(ClientIpUtils.IpRange.parse("192.168.1.5").contains("192.168.1.5"));
        assertFalse(ClientIpUtils.IpRange.parse("192.168.1.5").contains("192.168.1.6"));
        assertEquals("192.168.1.6", ClientIpUtils.normalize("192.168.1.6:51234"));
        assertEquals("::1", ClientIpUtils.normalize("[::1]:8080"));
        assertTrue(ClientIpUtils.IpRange.parse("::1/128").contains("::1"));
        // 2.7 非法配置项被跳过（fail-closed：写错格式只会更严格，不会放开转发头）
        assertTrue(ClientIpUtils.parseTrustedProxies("not-an-ip, 10.0.0.0/8").size() == 1);
        assertTrue(ClientIpUtils.parseTrustedProxies("10.0.0.0/33").isEmpty());
    }

    // =========================================================================
    // 3. AI 接口配额
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("3. AI 接口配额：分钟频控与日配额超限返回 429，窗口恢复后可用")
    void test03_aiQuotaEnforced() throws Exception {
        String username = "harden_ai_" + UUID.randomUUID().toString().substring(0, 8);
        String token = tokenForUser(username);
        Long userId = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username)).getId();

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String dayKey = RedisKeyConstants.aiQuotaDayKey(userId, today);

        // 清理，保证从零开始
        stringRedisTemplate.delete(dayKey);
        for (int i = -2; i <= 2; i++) {
            stringRedisTemplate.delete(RedisKeyConstants.aiQuotaMinuteKey(userId,
                    System.currentTimeMillis() / 60000L + i));
        }

        // 3.1 正常调用：应成功（未配置 DEEPSEEK_API_KEY，走本地降级但接口是 200）
        callAiDescription(token).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        // 3.2 分钟频控：把当前分钟计数打到上限（10），第 11 次必须 429
        String minuteKey = RedisKeyConstants.aiQuotaMinuteKey(userId, System.currentTimeMillis() / 60000L);
        stringRedisTemplate.opsForValue().set(minuteKey, "10");
        System.out.println("[AI 分钟频控] 429 响应: " + callAiDescription(token).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        callAiDescription(token)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("AI 助手调用过于频繁，请稍后再试"));
        Long minuteTtl = stringRedisTemplate.getExpire(minuteKey, TimeUnit.SECONDS);
        assertNotNull(minuteTtl);
        assertTrue(minuteTtl > 0 && minuteTtl <= 60, "分钟频控键必须有 60 秒内的 TTL，实际=" + minuteTtl);

        // 3.3 恢复窗口：清空分钟计数后立即可用（证明限流不是永久的）
        stringRedisTemplate.delete(minuteKey);
        callAiDescription(token).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        // 3.4 日配额：打满后所有 AI 接口（三个共用同一配额）都返回 429
        stringRedisTemplate.opsForValue().set(dayKey, "50");
        System.out.println("[AI 日配额] 429 响应: " + callAiDescription(token).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        System.out.printf("[AI 日配额] Redis %s=%s, TTL(s)=%s%n", dayKey,
                stringRedisTemplate.opsForValue().get(dayKey), stringRedisTemplate.getExpire(dayKey));
        callAiDescription(token)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("AI 助手今日调用次数已达上限（50 次/天），请明天再试"));

        mockMvc.perform(post("/ai/goods/price")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"二手自行车\",\"originalPrice\":800,\"conditionLevel\":\"95新\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));

        Long dayTtl = stringRedisTemplate.getExpire(dayKey, TimeUnit.SECONDS);
        assertNotNull(dayTtl);
        assertTrue(dayTtl > 0 && dayTtl <= 24 * 3600, "日配额键必须有 TTL（否则用户会被永久卡住），实际=" + dayTtl);

        // 3.5 新的一天（换个日期键）自动恢复
        stringRedisTemplate.delete(dayKey);
        callAiDescription(token).andExpect(status().isOk());
    }

    // =========================================================================
    // 4. /auth/refresh 与 /auth/logout 限流
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("4. /auth/refresh 与 /auth/logout：按 IP 与令牌指纹双维度限流，超限 429")
    void test04_tokenEndpointsRateLimited() throws Exception {
        String refreshIpKey = RedisKeyConstants.authRefreshIpKey("127.0.0.1");
        String logoutIpKey = RedisKeyConstants.authLogoutIpKey("127.0.0.1");
        StringRedisTemplate redis = stringRedisTemplate;
        redis.delete(refreshIpKey);
        redis.delete(logoutIpKey);

        // 4.1 /auth/refresh：同一个（伪造的）令牌反复提交 → 令牌指纹维度先触发上限（10 次/分钟）
        String fakeToken = "eyJhbGciOiJIUzI1NiJ9." + UUID.randomUUID() + ".signature";
        String refreshTokenKey = RedisKeyConstants.authRefreshTokenKey(
                com.campustrade.security.TokenHashUtils.rateLimitFingerprint(fakeToken));
        redis.delete(refreshTokenKey);

        int limitedRefresh = 0;
        for (int i = 1; i <= 11; i++) {
            MvcResult result = mockMvc.perform(post("/auth/refresh")
                            .with(remoteAddr("127.0.0.1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + fakeToken + "\"}"))
                    .andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            if (body.path("code").asInt() == 429) {
                limitedRefresh = i;
                break;
            }
            assertEquals(401, body.path("code").asInt(),
                    "非法令牌必须先被鉴权拒绝（401），而不是被限流掩盖: " + body);
        }
        System.out.printf("[refresh 限流] Redis %s=%s, %s=%s；第 %d 次请求被拒%n",
                refreshIpKey, redis.opsForValue().get(refreshIpKey),
                refreshTokenKey, redis.opsForValue().get(refreshTokenKey), limitedRefresh);
        assertEquals(11, limitedRefresh, "第 11 次（超过 10 次/分钟）必须返回 429");
        String refreshResult = mockMvc.perform(post("/auth/refresh")
                        .with(remoteAddr("127.0.0.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + fakeToken + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("该令牌请求过于频繁，请稍后再试"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("[refresh 限流] 429 响应: " + refreshResult);
        Long refreshTtl = redis.getExpire(refreshTokenKey, TimeUnit.SECONDS);
        assertNotNull(refreshTtl);
        assertTrue(refreshTtl > 0 && refreshTtl <= 60, "令牌指纹限流键必须有 TTL，实际=" + refreshTtl);

        // 4.2 /auth/logout：同一个 Bearer 令牌反复提交 → 同样触发 429
        String logoutTokenKey = RedisKeyConstants.authLogoutTokenKey(
                com.campustrade.security.TokenHashUtils.rateLimitFingerprint(fakeToken));
        redis.delete(logoutTokenKey);
        redis.delete(RedisKeyConstants.jwtBlacklistKey(fakeToken));

        MvcResult lastLogout = null;
        for (int i = 1; i <= 11; i++) {
            lastLogout = mockMvc.perform(post("/auth/logout")
                            .with(remoteAddr("127.0.0.1"))
                            .header("Authorization", "Bearer " + fakeToken))
                    .andReturn();
        }
        JsonNode lastBody = objectMapper.readTree(lastLogout.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        System.out.printf("[logout 限流] Redis %s=%s；第 11 次响应: %s%n",
                logoutTokenKey, redis.opsForValue().get(logoutTokenKey), lastBody);
        assertEquals(429, lastBody.path("code").asInt(),
                "第 11 次 /auth/logout 必须被限流: " + lastBody);
        assertEquals(429, lastLogout.getResponse().getStatus());
        Long logoutTtl = redis.getExpire(logoutTokenKey, TimeUnit.SECONDS);
        assertNotNull(logoutTtl);
        assertTrue(logoutTtl > 0 && logoutTtl <= 60, "登出令牌指纹限流键必须有 TTL，实际=" + logoutTtl);

        // 清理：避免影响其他用例的 IP 维度计数
        redis.delete(refreshTokenKey);
        redis.delete(logoutTokenKey);
        redis.delete(refreshIpKey);
        redis.delete(logoutIpKey);
    }

    // =========================================================================
    // 5. 举报每日限流的原子性与 TTL
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 举报每日限流：并发 20 次不超发（成功数=10），计数键带 TTL，无永久卡死")
    void test05_reportDailyLimitIsAtomic() throws Exception {
        Long reporterId = 9910000L + (long) (Math.random() * 9000);
        initUser(reporterId, "harden_reporter_" + UUID.randomUUID().toString().substring(0, 6));
        Long sellerId = reporterId + 1;
        initUser(sellerId, "harden_seller_" + UUID.randomUUID().toString().substring(0, 6));

        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String limitKey = RedisKeyConstants.reportDailyLimitKey(reporterId, today);
        stringRedisTemplate.delete(limitKey);

        // 20 个互不相同的举报目标：若限额失效，20 次都会成功
        int attempts = 20;
        List<Long> goodsIds = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Goods goods = Goods.builder()
                    .sellerId(sellerId)
                    .schoolId(1L)
                    .categoryId(1L)
                    .title("限流并发夹具 " + UUID.randomUUID().toString().substring(0, 6))
                    .description("举报限流并发测试商品")
                    .price(new BigDecimal("10.00"))
                    .conditionLevel("95新")
                    .status(GoodsStatus.ON_SALE.getCode())
                    .viewCount(0)
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            goodsMapper.insert(goods);
            goodsIds.add(goods.getId());
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                final Long goodsId = goodsIds.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        reportService.submitReport(reporterId, CreateReportRequest.builder()
                                .targetType("GOODS")
                                .targetId(goodsId)
                                .reasonType("FRAUD")
                                .description("并发限流回归")
                                .build());
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getCode() == 429) {
                            rateLimited.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发提交必须在超时前全部结束");
        } finally {
            pool.shutdownNow();
        }

        System.out.printf("[举报限流] 成功=%d, 429=%d, 其他=%d, redis计数=%s%n",
                success.get(), rateLimited.get(), other.get(), stringRedisTemplate.opsForValue().get(limitKey));

        assertEquals(0, other.get(), "不应出现限流/成功之外的失败");
        assertEquals(10, success.get(), "每日上限 10 次：并发 20 次必须恰好成功 10 次（不超发）");
        assertEquals(10, rateLimited.get(), "其余 10 次必须是 429 限流");

        Long persisted = reportMapper.selectCount(
                new LambdaQueryWrapper<Report>().eq(Report::getReporterId, reporterId));
        assertEquals(10L, persisted, "数据库中的举报条数必须与成功次数一致（不超发）");

        // 计数键必须带 TTL：不会因为中途失败而变成永不失效的键
        Long ttl = stringRedisTemplate.getExpire(limitKey, TimeUnit.SECONDS);
        assertNotNull(ttl, "举报限流计数键必须存在");
        assertTrue(ttl > 0 && ttl <= 24 * 3600, "举报限流计数键必须有 TTL，实际=" + ttl);

        // 清理
        stringRedisTemplate.delete(limitKey);
        reportMapper.delete(new LambdaQueryWrapper<Report>().eq(Report::getReporterId, reporterId));
        goodsMapper.deleteBatchIds(goodsIds);
    }

    // =========================================================================
    // 6. 上传内容安全
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("6. 上传：声明的 image/svg+xml 被忽略，对象 Content-Type 为服务端按扩展名映射的值")
    void test06_uploadContentTypeMappedServerSide() throws Exception {
        String token = tokenForUser("harden_upload_" + UUID.randomUUID().toString().substring(0, 8));

        byte[] png = tinyPng(4, 4);
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/svg+xml", png);

        MvcResult result = mockMvc.perform(multipart("/file/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String url = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").asText();
        String objectName = url.substring(url.indexOf("goods/"));
        StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                .bucket("campustrade-test")
                .object(objectName)
                .build());
        System.out.printf("[上传类型] 声明=%s → 对象 %s 实际 Content-Type=%s%n",
                "image/svg+xml", objectName, stat.contentType());
        assertEquals("image/png", stat.contentType(),
                "对象 Content-Type 必须由服务端按扩展名映射，而不是采信客户端声明的 image/svg+xml");
    }

    @Test
    @Order(7)
    @DisplayName("7. 上传：超大尺寸图片（20000x20000 头部）被拒绝 400，且不会落进对象存储")
    void test07_oversizedImageRejected() throws Exception {
        String token = tokenForUser("harden_big_" + UUID.randomUUID().toString().substring(0, 8));

        byte[] bomb = pngWithDeclaredSize(20000, 20000);
        MockMultipartFile file = new MockMultipartFile(
                "file", "bomb.png", "image/png", bomb);

        MvcResult result = mockMvc.perform(multipart("/file/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("图片尺寸过大，单边不得超过 10000 像素"))
                .andReturn();
        System.out.println("[上传尺寸校验] " + result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        // 正常尺寸的图片仍然可以上传（防止"一律拒绝"式的过度修复）
        MockMultipartFile ok = new MockMultipartFile("file", "ok.png", "image/png", tinyPng(64, 32));
        mockMvc.perform(multipart("/file/upload")
                        .file(ok)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // =========================================================================
    // 7. 同一校园邮箱只能被一个账号认证
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("8. 同一校园邮箱在第二个账号上核销：明确业务错误 409，且不会写入第二条 SUCCESS")
    void test08_sameSchoolEmailCannotBeVerifiedTwice() throws Exception {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String sharedEmail = "harden_shared_" + runId + "@mails.tsinghua.edu.cn";

        // 账号 A：直接落一条"已认证"记录（等价于 A 已用该邮箱完成认证）
        Long userAId = 9920000L + (long) (Math.random() * 9000);
        initUser(userAId, "harden_a_" + runId);
        LocalDateTime now = LocalDateTime.now();
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(userAId)
                .schoolId(1L)
                .studentNumber("S9A" + runId)
                .schoolEmail(sharedEmail)
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(now)
                .createdTime(now)
                .build());

        // 账号 B：走完整流程（下发验证码 → 核销）
        String usernameB = "harden_b_" + runId;
        String tokenB = tokenForUser(usernameB);
        Long userBId = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameB)).getId();

        StudentVerifyDTO submit = new StudentVerifyDTO();
        submit.setSchoolId(1L);
        submit.setStudentNumber("S9B" + runId);
        submit.setSchoolEmail(sharedEmail);
        mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String code = stringRedisTemplate.opsForValue().get(RedisKeyConstants.studentVerifyKey(sharedEmail));
        assertNotNull(code, "验证码应已下发到 Redis");

        StudentVerifyCodeDTO verify = new StudentVerifyCodeDTO();
        verify.setSchoolEmail(sharedEmail);
        verify.setVerifyCode(code);
        MvcResult result = mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verify)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andReturn();
        String message = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("message").asText();
        assertTrue(message.contains("已被其他账号完成认证"), "必须给出明确业务文案: " + message);
        System.out.println("[校园邮箱唯一性] " + message);

        // B 仍然没有被写成 SUCCESS
        Long bSuccess = studentVerifyMapper.selectCount(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, userBId)
                .eq(StudentVerify::getVerifyStatus, StudentVerifyStatus.SUCCESS.getCode()));
        assertEquals(0L, bSuccess, "第二个账号绝不能核销成功");

        // 清理
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, userAId));
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, userBId));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyKey(sharedEmail));
    }

    // =========================================================================
    // 8. V12 迁移产物
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("9. V12 产物：部分唯一索引存在且生效（重复 SUCCESS 被数据库拒绝）")
    void test09_v12PartialUniqueIndexEnforced() {
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='campus_trade' "
                        + "AND indexname='uk_student_verify_email_success'", Integer.class);
        assertEquals(1, indexCount, "V12 必须创建 uk_student_verify_email_success");

        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='campus_trade' "
                        + "AND indexname='uk_student_verify_email_success'", String.class);
        assertTrue(indexDef.contains("UNIQUE"), indexDef);
        assertTrue(indexDef.contains(StudentVerifyStatus.SUCCESS.getCode()),
                "必须是 WHERE verify_status='SUCCESS' 的部分索引: " + indexDef);

        // 行为验证：第二条同 (school_id, school_email) 的 SUCCESS 行必须被数据库拒绝
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String email = "harden_dup_" + runId + "@pku.edu.cn";
        Long firstUser = 9930000L + (long) (Math.random() * 4000);
        Long secondUser = firstUser + 5000;
        initUser(firstUser, "harden_dup1_" + runId);
        initUser(secondUser, "harden_dup2_" + runId);

        LocalDateTime now = LocalDateTime.now();
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(firstUser).schoolId(2L).studentNumber("DUP1" + runId)
                .schoolEmail(email).verifyStatus(StudentVerifyStatus.SUCCESS.getCode()).verifyTime(now).createdTime(now).build());

        try {
            studentVerifyMapper.insert(StudentVerify.builder()
                    .userId(secondUser).schoolId(2L).studentNumber("DUP2" + runId)
                    .schoolEmail(email).verifyStatus(StudentVerifyStatus.SUCCESS.getCode()).verifyTime(now).createdTime(now).build());
            fail("第二条 SUCCESS 必须被部分唯一索引拒绝");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("uk_student_verify_email_success")
                            || String.valueOf(expected.getMostSpecificCause().getMessage())
                            .contains("uk_student_verify_email_success"),
                    "违反的约束必须是被唯一索引兜底: " + expected.getMessage());
        } finally {
            studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                    .eq(StudentVerify::getSchoolEmail, email));
        }

        // PENDING 行不受限制（同一邮箱可以在多个账号上处于待核销状态）
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(firstUser).schoolId(2L).studentNumber("P1" + runId)
                .schoolEmail(email).verifyStatus(StudentVerifyStatus.PENDING.getCode()).createdTime(now).build());
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(secondUser).schoolId(2L).studentNumber("P2" + runId)
                .schoolEmail(email).verifyStatus(StudentVerifyStatus.PENDING.getCode()).createdTime(now).build());
        Long pending = studentVerifyMapper.selectCount(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getSchoolEmail, email)
                .eq(StudentVerify::getVerifyStatus, StudentVerifyStatus.PENDING.getCode()));
        assertEquals(2L, pending, "PENDING 行必须允许重复（部分索引只约束 SUCCESS 行）");
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getSchoolEmail, email));
    }

    @Test
    @Order(10)
    @DisplayName("10. V12 产物：12 条补齐的外键全部存在且为 NOT VALID（只约束新数据）")
    void test10_v12ForeignKeysAreNotValid() {
        List<String> fkNames = List.of(
                "fk_review_reviewer", "fk_review_reviewed_user", "fk_review_like_review", "fk_review_like_user",
                "fk_browse_history_user", "fk_browse_history_goods", "fk_search_history_user", "fk_report_reporter",
                "fk_admin_audit_log_admin", "fk_student_verify_user", "fk_student_verify_school", "fk_user_credit_user"
        );
        for (String name : fkNames) {
            List<String> rows = jdbcTemplate.query(
                    "SELECT conname || '|' || convalidated FROM pg_constraint "
                            + "WHERE connamespace='campus_trade'::regnamespace AND conname=?",
                    (rs, i) -> rs.getString(1), name);
            assertEquals(1, rows.size(), "V12 必须登记外键 " + name);
            assertEquals(name + "|false", rows.get(0),
                    "外键 " + name + " 必须是 NOT VALID（否则历史孤儿行会让迁移失败）");
        }

        // 反向确认：这些外键对"新数据"是立即生效的（NOT VALID 不等于不生效）
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try {
            jdbcTemplate.update("INSERT INTO campus_trade.user_credit "
                            + "(id, user_id, credit_score, trade_count, good_review_count, bad_review_count, "
                            + "completed_count, cancel_count, credit_level) "
                            + "VALUES (?, ?, 100, 0, 0, 0, 0, 0, 'GOOD')",
                    999000000L + Math.abs(runId.hashCode() % 1000), 777000000L + Math.abs(runId.hashCode() % 1000));
            fail("NOT VALID 外键必须仍然拦截新增的孤儿行");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("fk_user_credit_user")
                            || String.valueOf(expected.getMostSpecificCause().getMessage()).contains("fk_user_credit_user"),
                    "新数据的 FK 校验必须指向 fk_user_credit_user: " + expected.getMessage());
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private org.springframework.test.web.servlet.ResultActions callAiDescription(String token) throws Exception {
        AiDescriptionDTO dto = new AiDescriptionDTO();
        dto.setTitle("九成新捷安特山地车");
        dto.setConditionLevel("95新");
        dto.setOriginalDescription("骑行半年，无磕碰");
        return mockMvc.perform(post("/ai/goods/description")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)));
    }

    private void initUser(Long id, String username) {
        if (userMapper.selectById(id) == null) {
            LocalDateTime now = LocalDateTime.now();
            userMapper.insert(User.builder()
                    .id(id)
                    .username(username)
                    .password(UUID.randomUUID().toString())
                    .nickname(username)
                    .role("USER")
                    .status("ACTIVE")
                    .createdTime(now)
                    .updatedTime(now)
                    .build());
        }
    }

    /**
     * 直接落库建号并签发 Access Token。
     *
     * <p>刻意不走 {@code /auth/register}：注册接口有"单一来源 IP 每小时 20 次"的限频，
     * 而测试 JVM 内的所有请求都来自 127.0.0.1，用注册接口造夹具会挤占其他测试类的配额
     * （表现为别的用例拿到 429）。这里改为直接建号 + 本地签发令牌，
     * 与项目内其他测试（Stage6B / 一致性套件）的做法保持一致。</p>
     */
    private String tokenForUser(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            LocalDateTime now = LocalDateTime.now();
            user = User.builder()
                    .id(9940000L + (long) (Math.random() * 50000))
                    .username(username)
                    .password(UUID.randomUUID().toString())
                    .nickname(username)
                    .email(username + "@test.edu.cn")
                    .role("USER")
                    .status("ACTIVE")
                    .createdTime(now)
                    .updatedTime(now)
                    .build();
            userMapper.insert(user);
        }
        return jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
    }

    /** 用 JDK ImageIO 生成一张真实可解析的 PNG。 */
    private static byte[] tinyPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out), "JDK 应支持写出 PNG");
        return out.toByteArray();
    }

    /**
     * 构造一个"头部声明超大尺寸"的 PNG：先写出一张真实 PNG，再把 IHDR 的宽高改成指定值，
     * 并重算 IHDR 的 CRC（保证结构合法）。这样 {@code ImageReader#getWidth} 能读到巨大尺寸，
     * 但文件本体极小 —— 正是解压炸弹的形态。
     */
    private static byte[] pngWithDeclaredSize(int width, int height) throws Exception {
        byte[] png = tinyPng(2, 2);
        // 签名 8 字节 + 长度 4 + 类型 4 = 16，宽度 4 字节（16..19），高度 4 字节（20..23），CRC 在 29..32
        writeInt(png, 16, width);
        writeInt(png, 20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17); // 类型(4) + 数据(13)
        writeInt(png, 29, (int) crc.getValue());
        return png;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
