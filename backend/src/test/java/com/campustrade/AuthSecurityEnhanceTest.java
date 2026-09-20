package com.campustrade;

import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RefreshTokenRequest;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.entity.User;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.support.TestCredentials;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 7-B: 认证安全加固与双 Token 续期测试套件
 * 核心验证：
 * 1. JWT Token 类型混淆防御：Refresh Token 作为 Bearer 访问受保护接口必须被拦截 (401)；
 * 2. Access Token 正确鉴权：合法 Access Token 可以正常访问受保护接口 (200)；
 * 3. Refresh Token 接口功能闭环：POST /api/auth/refresh 正常续签并返回新 Access Token；
 * 4. Refresh Token 白名单与伪造防御：未在 Redis 白名单或伪造的 Token 无法刷新；
 * 5. 令牌内 userId 与数据库用户不一致时必须 401（防"账号注销后同名重建"被旧令牌认领）；
 * 6. 64 位整型 ID 精度安全验证：Jackson 序列化 Long 型 ID 为 JSON 字符串。
 *
 * <h2>用例之间互不依赖（本批次收尾改动）</h2>
 * <p>此前该类用 {@code @TestMethodOrder(OrderAnnotation.class)} + 静态字段
 * （{@code accessToken} / {@code refreshToken} / {@code userId} 由 {@code @Order(1)} 的用例赋值）
 * 传递状态，导致"单独运行某个用例"必然失败（前置用例没跑，静态字段为 null），
 * 也把用例的正确性建立在了执行顺序上——尤其是 5 号用例会删除 Refresh 白名单，
 * 只要它先于 4 号用例执行，4 号用例就会假失败。</p>
 * <p>现在改为：<b>每个用例在 {@link #registerAndLoginFreshUser()} 中自带前置</b>
 * （各自的随机账号 + 注册 + 登录），状态全部是实例字段，不含任何静态跨用例状态，
 * 也不再有 {@code @Order}。因此任意单个用例都可以独立运行、结果一致。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AuthSecurityEnhanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    /**
     * 本用例专属的来源 IP。
     *
     * <p>注册接口有一条"同一来源 IP 每小时最多 N 次（默认 20）"的限频（{@code AuthServiceImpl}）。
     * MockMvc 的默认 {@code remoteAddr} 是 {@code 127.0.0.1}，让每个用例都注册一个新账号会把
     * 同一测试 JVM（同一 Redis 容器）内所有测试类的注册次数累加进同一个桶，
     * 用例越多越容易在正式用例上撞出 429——那是限流器的正确行为，却会让测试变得不稳定。</p>
     *
     * <p>因此每个用例（JUnit 默认为每个用例新建实例）各自分配一个独立 IP，
     * 使"注册限频"维度按用例隔离。取 {@code 10.207.x.y} 是为了不与
     * {@code TrustedProxyIpResolutionTests} 使用的 203.0.113.7 / 198.51.100.9 / 172.20.0.9 重叠
     * （测试环境 {@code security.trusted-proxies} 为空，一律按 remoteAddr 计维度）。</p>
     */
    private final String clientIp = "10.207."
            + ThreadLocalRandom.current().nextInt(1, 200) + "."
            + ThreadLocalRandom.current().nextInt(2, 250);

    // ===== 以下全部为实例字段：由 @BeforeEach 为本用例准备，不跨用例共享 =====

    private String testUsername;
    private String testPassword;
    private String accessToken;
    private String refreshToken;
    private Long userId;

    /** 把请求的 TCP 对端地址设为给定值（只影响本用例的注册限频维度）。 */
    private static RequestPostProcessor fromClientIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /**
     * 每个用例的前置：随机账号 → 注册 → 登录 → 解析出 Access / Refresh Token 与 userId。
     *
     * <p>不依赖任何其它用例，因此任意用例单独运行都成立。</p>
     */
    @BeforeEach
    void registerAndLoginFreshUser() throws Exception {
        testUsername = "sec_test_" + UUID.randomUUID().toString().substring(0, 8);
        // 运行时随机生成测试口令，源码中不固化任何可用口令；注册与登录共用同一值
        testPassword = TestCredentials.randomPassword();

        RegisterRequestDTO registerDTO = new RegisterRequestDTO();
        registerDTO.setUsername(testUsername);
        registerDTO.setPassword(testPassword);
        registerDTO.setEmail(testUsername + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .with(fromClientIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername(testUsername);
        loginDTO.setPassword(testPassword);

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .with(fromClientIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        accessToken = root.path("data").path("accessToken").asText();
        refreshToken = root.path("data").path("refreshToken").asText();

        assertNotNull(accessToken, "Access Token 不能为空");
        assertNotNull(refreshToken, "Refresh Token 不能为空");

        userId = jwtTokenProvider.getUserId(accessToken);
        assertNotNull(userId, "UserId 必须解析自 Token 载荷");
    }

    @Test
    @DisplayName("1. 注册并登录后的令牌类型与载荷正确 (access / refresh / userId)")
    void test01_registerAndLogin() {
        assertEquals("access", jwtTokenProvider.getTokenType(accessToken));
        assertEquals("refresh", jwtTokenProvider.getTokenType(refreshToken));

        User user = userMapper.selectById(userId);
        assertNotNull(user, "登录令牌里的 userId 必须对应真实用户");
        assertEquals(testUsername, user.getUsername());
    }

    @Test
    @DisplayName("2. 验证 Token 类型混淆: 使用 7 天 Refresh Token 作为 Bearer 访问 API 必须被严格拦截 (401)")
    void test02_refreshTokenCannotAccessProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("3. 验证合法 Access Token 正常访问受保护接口 (200)")
    void test03_accessTokenCanAccessProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(testUsername));
    }

    @Test
    @DisplayName("4. 验证 POST /auth/refresh 端点: 使用合法 Refresh Token 成功续签新 Access Token")
    void test04_refreshTokenSuccess() throws Exception {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        JsonNode root = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String newAccessToken = root.path("data").path("accessToken").asText();

        assertNotNull(newAccessToken);
        assertEquals("access", jwtTokenProvider.getTokenType(newAccessToken));
        assertEquals(userId, jwtTokenProvider.getUserId(newAccessToken), "续签令牌必须仍属于同一 userId");

        // 验证续签的 Access Token 可以访问受保护接口
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(testUsername));
    }

    @Test
    @DisplayName("5. 验证白名单防御: 当 Refresh Token 不在 Redis 白名单中，刷新必须被拒绝 (401)")
    void test05_refreshFailsWhenNotInRedisWhitelist() throws Exception {
        // 清除 Redis 中的 Refresh Token 白名单
        stringRedisTemplate.delete(RedisKeyConstants.JWT_REFRESH_PREFIX + userId);

        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .build();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("6. 验证令牌内 userId 与数据库用户不一致时必须 401（防同名账号重建后旧令牌被认领）")
    void test06_tokenUserIdMismatchIsRejected() throws Exception {
        // 构造"同名但 userId 不同"的令牌：sub 仍是本用例的用户名（数据库里查得到），
        // 但载荷里的 userId 指向另一个 id —— 这正是"账号注销/删除后同名重建"时旧令牌的形状。
        long foreignUserId = userId + 1_000_000L;
        String mismatchedToken =
                jwtTokenProvider.generateAccessToken(foreignUserId, testUsername, "USER");

        // 先确认该令牌自身是合法的 ACCESS 令牌（问题只出在 userId 与库中用户不一致）
        assertEquals("access", jwtTokenProvider.getTokenType(mismatchedToken));
        assertEquals(foreignUserId, jwtTokenProvider.getUserId(mismatchedToken));

        MvcResult result = mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + mismatchedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();

        System.out.println("[JWT userId 比对] token.userId=" + foreignUserId
                + ", db.userId=" + userId + " → HTTP "
                + result.getResponse().getStatus() + " body=" + result.getResponse().getContentAsString());

        // 对照组：同一次前置里签发的合法令牌仍然可用，证明 401 来自 userId 比对而非其它原因
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(testUsername));
    }

    @Test
    @DisplayName("7. 验证 Long 型 ID 序列化为 JSON String (防止 Web 前端精度截断)")
    void test07_longIdSerializedAsString() throws Exception {
        User user = userMapper.selectById(userId);
        assertNotNull(user);

        String json = objectMapper.writeValueAsString(user);
        JsonNode node = objectMapper.readTree(json);

        JsonNode idNode = node.get("id");
        assertNotNull(idNode);
        assertTrue(idNode.isTextual(), "Long 型 ID 必须序列化为 JSON 字符串类型 (isTextual)");
        assertEquals(String.valueOf(userId), idNode.asText());
    }
}
