package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.*;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtAuthenticationFilter;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.support.TestCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 1 用户中心与校园认证核心集成测试
 * 严格覆盖要求中的 9 大验证点
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage1Tests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 测试共享数据
    private static final String TEST_USERNAME = "test_stu_" + UUID.randomUUID().toString().substring(0, 8);
    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册/登录/加密断言共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();
    // 负向用例使用的第二种口令（仅用于"不同口令"场景，与 TEST_PASSWORD 不同）
    private static final String TEST_PASSWORD_ALT = TestCredentials.randomPassword();
    private static final String TEST_EMAIL = TEST_USERNAME + "@test.edu.cn";
    private static final String SCHOOL_EMAIL = TEST_USERNAME + "@mails.tsinghua.edu.cn";

    private static String userAccessToken;

    @Test
    @Order(1)
    @DisplayName("1. 验证用户注册成功与密码加密")
    void test1_RegisterSuccess() throws Exception {
        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername(TEST_USERNAME);
        dto.setPassword(TEST_PASSWORD);
        dto.setEmail(TEST_EMAIL);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证数据库已成功写入
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME));
        assertNotNull(user, "用户记录应存在于数据库");
        assertNotEquals(TEST_PASSWORD, user.getPassword(), "数据库中绝不能存储明文密码");
    }

    @Test
    @Order(2)
    @DisplayName("2. 验证重复用户名注册失败")
    void test2_DuplicateUsernameFails() throws Exception {
        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername(TEST_USERNAME);
        dto.setPassword(TEST_PASSWORD_ALT);
        dto.setEmail("another_" + UUID.randomUUID().toString().substring(0, 6) + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已被占用，请更换其他用户名"));
    }

    @Test
    @Order(3)
    @DisplayName("3. 验证密码 BCrypt 加密匹配")
    void test3_BcryptPasswordMatches() {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME));
        assertNotNull(user);
        assertTrue(passwordEncoder.matches(TEST_PASSWORD, user.getPassword()), "BCrypt 密码哈希匹配应为 true");
        assertFalse(passwordEncoder.matches("WrongPassword", user.getPassword()), "错误密码匹配应为 false");
    }

    @Test
    @Order(4)
    @DisplayName("4. 验证登录成功并返回有效 JWT (Access & Refresh)")
    void test4_LoginSuccessReturnsJwt() throws Exception {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setUsername(TEST_USERNAME);
        dto.setPassword(TEST_PASSWORD);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.userInfo.username").value(TEST_USERNAME))
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(responseJson);
        userAccessToken = root.path("data").path("accessToken").asText();
        assertNotNull(userAccessToken);
        assertFalse(userAccessToken.isBlank());
    }

    @Test
    @Order(5)
    @DisplayName("5. 验证无 Token 访问受保护接口失败 (401 Unauthorized)")
    void test5_AccessProtectedWithoutTokenFails() throws Exception {
        mockMvc.perform(get("/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(6)
    @DisplayName("6. 验证携带有效 Token 访问受保护接口成功")
    void test6_AccessProtectedWithTokenSuccess() throws Exception {
        assertNotNull(userAccessToken, "前置测试应已生成 Token");

        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @Order(7)
    @DisplayName("7. 验证校园认证验证码生成并存入 Redis (5分钟 TTL)")
    void test7_StudentVerifyRedisCodeSaved() throws Exception {
        StudentVerifyDTO dto = new StudentVerifyDTO();
        dto.setSchoolId(1L); // 清华大学 @mails.tsinghua.edu.cn
        dto.setStudentNumber("2026998877");
        dto.setSchoolEmail(SCHOOL_EMAIL);

        mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证 Redis 中存在验证码且 TTL 大于 0
        String redisKey = "student:verify:" + SCHOOL_EMAIL;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        assertNotNull(cachedCode, "Redis 中必须存在校园验证码");
        assertEquals(6, cachedCode.length(), "验证码长度应为 6 位");

        Long expireSeconds = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertNotNull(expireSeconds);
        assertTrue(expireSeconds > 0 && expireSeconds <= 300, "验证码 TTL 应为 5 分钟 (<=300s)");
    }

    @Test
    @Order(8)
    @DisplayName("8. 验证校园邮箱验证码核销与认证成功")
    void test8_StudentVerifyCodeSuccess() throws Exception {
        String redisKey = "student:verify:" + SCHOOL_EMAIL;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        assertNotNull(cachedCode);

        StudentVerifyCodeDTO codeDTO = new StudentVerifyCodeDTO();
        codeDTO.setSchoolEmail(SCHOOL_EMAIL);
        codeDTO.setVerifyCode(cachedCode);

        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 校验数据库状态更新为 SUCCESS
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME));
        StudentVerify verifyRecord = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .eq(StudentVerify::getVerifyStatus, "SUCCESS")
        );
        assertNotNull(verifyRecord, "认证状态应已更新为 SUCCESS");
        assertNotNull(verifyRecord.getVerifyTime(), "应记录认证通过时间");

        // 验证核销后验证码已从 Redis 清理
        assertNull(stringRedisTemplate.opsForValue().get(redisKey), "核销后 Redis 验证码应被清除");

        // 重新调用 /user/profile 验证返回认证学校与状态
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.schoolName").value("清华大学"));
    }

    @Test
    @Order(9)
    @DisplayName("9. 验证新用户注册自动创建初始信用分 (100分)")
    void test9_UserCreditAutoCreated() {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME));
        assertNotNull(user);

        UserCredit userCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, user.getId())
        );

        assertNotNull(userCredit, "用户注册后必须自动生成信用档案记录");
        assertEquals(100, userCredit.getCreditScore(), "初始信用分必须为 100 分");
        assertEquals(0, userCredit.getTradeCount(), "初始交易数必须为 0");
        assertEquals(0, userCredit.getGoodReviewCount(), "初始好评数必须为 0");
    }

    @Test
    @Order(10)
    @DisplayName("10. 验证安全登出与 Token 黑名单失效")
    void test10_LogoutAndTokenBlacklisted() throws Exception {
        // 登出
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证 Redis 黑名单存在该 Token
        Boolean hasBlacklist = stringRedisTemplate.hasKey(JwtAuthenticationFilter.BLACKLIST_PREFIX + userAccessToken);
        assertTrue(Boolean.TRUE.equals(hasBlacklist), "登出后 Token 应被写入 Redis 黑名单");

        // 再次使用该 Token 请求应被拒绝 (401)
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
