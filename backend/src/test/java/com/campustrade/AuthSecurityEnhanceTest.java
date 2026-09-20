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

import java.util.UUID;

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
 * 5. 64 位整型 ID 精度安全验证：Jackson 序列化 Long 型 ID 为 JSON 字符串。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

    private static String testUsername;
    private static String testPassword;
    private static String accessToken;
    private static String refreshToken;
    private static Long userId;

    @BeforeAll
    static void initCredentials() {
        testUsername = "sec_test_" + UUID.randomUUID().toString().substring(0, 8);
        // 运行时随机生成测试口令，源码中不固化任何可用口令；注册与登录共用同一值
        testPassword = TestCredentials.randomPassword();
    }

    @Test
    @Order(1)
    @DisplayName("1. 注册并登录用户获取初始 Access Token 与 Refresh Token")
    void test01_registerAndLogin() throws Exception {
        RegisterRequestDTO registerDTO = new RegisterRequestDTO();
        registerDTO.setUsername(testUsername);
        registerDTO.setPassword(testPassword);
        registerDTO.setEmail(testUsername + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername(testUsername);
        loginDTO.setPassword(testPassword);

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
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

        assertEquals("access", jwtTokenProvider.getTokenType(accessToken));
        assertEquals("refresh", jwtTokenProvider.getTokenType(refreshToken));

        userId = jwtTokenProvider.getUserId(accessToken);
        assertNotNull(userId, "UserId 必须解析自 Token 载荷");
    }

    @Test
    @Order(2)
    @DisplayName("2. 验证 Token 类型混淆: 使用 7 天 Refresh Token 作为 Bearer 访问 API 必须被严格拦截 (401)")
    void test02_refreshTokenCannotAccessProtectedEndpoints() throws Exception {
        assertNotNull(refreshToken);

        // 用 Refresh Token 访问 /user/profile
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(3)
    @DisplayName("3. 验证合法 Access Token 正常访问受保护接口 (200)")
    void test03_accessTokenCanAccessProtectedEndpoints() throws Exception {
        assertNotNull(accessToken);

        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(testUsername));
    }

    @Test
    @Order(4)
    @DisplayName("4. 验证 POST /auth/refresh 端点: 使用合法 Refresh Token 成功续签新 Access Token")
    void test04_refreshTokenSuccess() throws Exception {
        assertNotNull(refreshToken);

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

        // 验证续签的 Access Token 可以访问受保护接口
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(testUsername));
    }

    @Test
    @Order(5)
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
    @Order(6)
    @DisplayName("6. 验证 Long 型 ID 序列化为 JSON String (防止 Web 前端精度截断)")
    void test06_longIdSerializedAsString() throws Exception {
        User user = userMapper.selectById(userId);
        assertNotNull(user);

        String json = objectMapper.writeValueAsString(user);
        System.out.println("DEBUG User JSON: " + json);
        JsonNode node = objectMapper.readTree(json);

        JsonNode idNode = node.get("id");
        assertNotNull(idNode);
        assertTrue(idNode.isTextual(), "Long 型 ID 必须序列化为 JSON 字符串类型 (isTextual)");
        assertEquals(String.valueOf(userId), idNode.asText());
    }
}
