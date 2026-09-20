package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.config.VerifyMailProdGuard;
import com.campustrade.config.VerifyProperties;
import com.campustrade.dto.*;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtAuthenticationFilter;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.service.mail.VerifyCodeMailSender;
import com.campustrade.support.TestCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    @DisplayName("7. 验证码经邮件通道下发：响应/日志不含验证码、Redis 落码并建立双向限流计数")
    void test7_StudentVerifyCodeSentWithoutLeaking() throws Exception {
        StudentVerifyDTO dto = new StudentVerifyDTO();
        dto.setSchoolId(1L); // 清华大学 @mails.tsinghua.edu.cn
        dto.setStudentNumber("2026998877");
        dto.setSchoolEmail(SCHOOL_EMAIL);

        MvcResult result = mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(StudentVerifyService.VERIFY_CODE_SENT_MESSAGE))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 1. 响应体不再回传验证码：data 必须为空值，整段响应文本里也不得出现验证码
        JsonNode root = objectMapper.readTree(responseBody);
        assertTrue(root.get("data").isNull(), "响应 data 必须为空：验证码不能再经响应回传");
        assertFalse(responseBody.contains("测试阶段验证码"), "响应不得再出现联调验证码字段");

        // 2. 验证码只存在于 Redis（5 分钟 TTL），由邮件（或本地开发日志）送达学生
        String codeKey = RedisKeyConstants.studentVerifyKey(SCHOOL_EMAIL);
        String cachedCode = stringRedisTemplate.opsForValue().get(codeKey);
        assertNotNull(cachedCode, "Redis 中必须存在校园验证码");
        assertTrue(cachedCode.matches("\\d{6}"), "验证码必须是 6 位数字");
        assertFalse(responseBody.contains(cachedCode), "响应体中绝不能出现验证码明文");

        Long codeExpire = stringRedisTemplate.getExpire(codeKey, TimeUnit.SECONDS);
        assertNotNull(codeExpire);
        assertTrue(codeExpire > 0 && codeExpire <= 300, "验证码 TTL 应为 5 分钟 (<=300s)");

        // 3. 双向限流计数与 TTL：发起人×邮箱维度 24 小时、用户维度 10 分钟
        Long currentUserId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME)).getId();
        String userEmailLimitKey = RedisKeyConstants.studentVerifySendUserEmailKey(currentUserId, SCHOOL_EMAIL);
        String userLimitKey = RedisKeyConstants.studentVerifySendUserKey(currentUserId);

        assertEquals("1", stringRedisTemplate.opsForValue().get(userEmailLimitKey),
                "首次下发后（发起人×邮箱）维度计数应为 1");
        Long emailLimitTtl = stringRedisTemplate.getExpire(userEmailLimitKey, TimeUnit.SECONDS);
        assertNotNull(emailLimitTtl);
        assertTrue(emailLimitTtl > 23 * 3600 && emailLimitTtl <= 24 * 3600,
                "（发起人×邮箱）维度限流窗口应为 24 小时，实际 TTL=" + emailLimitTtl);

        assertEquals("1", stringRedisTemplate.opsForValue().get(userLimitKey),
                "首次下发后用户维度计数应为 1");
        Long userLimitTtl = stringRedisTemplate.getExpire(userLimitKey, TimeUnit.SECONDS);
        assertNotNull(userLimitTtl);
        assertTrue(userLimitTtl > 9 * 60 && userLimitTtl <= 10 * 60,
                "用户维度限流窗口应为 10 分钟，实际 TTL=" + userLimitTtl);

        // 新验证码下发后不应残留旧的失败计数
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                RedisKeyConstants.studentVerifyFailKey(currentUserId, SCHOOL_EMAIL))),
                "下发新验证码时必须重置失败计数");

        // 4. 邮件通道策略（不发真实邮件，只验证通道选择与失败行为）
        assertMailChannelPolicy();
    }

    @Test
    @Order(8)
    @DisplayName("8. 验证码核销、5 次失败即作废、重发恢复与（发起人×邮箱）/用户双向限流")
    void test8_StudentVerifyCodeSuccessAndLockout() throws Exception {
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME)).getId();
        String codeKey = RedisKeyConstants.studentVerifyKey(SCHOOL_EMAIL);
        String failKey = RedisKeyConstants.studentVerifyFailKey(userId, SCHOOL_EMAIL);
        String userEmailLimitKey = RedisKeyConstants.studentVerifySendUserEmailKey(userId, SCHOOL_EMAIL);

        String originalCode = stringRedisTemplate.opsForValue().get(codeKey);
        assertNotNull(originalCode, "前置用例应已下发验证码");

        StudentVerifyCodeDTO codeDTO = new StudentVerifyCodeDTO();
        codeDTO.setSchoolEmail(SCHOOL_EMAIL);

        // 1. 连续输错 4 次：返回 400，失败计数逐次递增，验证码仍有效
        for (int i = 1; i <= 4; i++) {
            codeDTO.setVerifyCode(wrongCodeOf(originalCode));
            mockMvc.perform(post("/student/verify/code")
                            .header("Authorization", "Bearer " + userAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(codeDTO)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("验证码错误，请重新输入"));
            assertEquals(String.valueOf(i), stringRedisTemplate.opsForValue().get(failKey),
                    "第 " + i + " 次失败后计数应为 " + i);
        }
        assertNotNull(stringRedisTemplate.opsForValue().get(codeKey), "未达阈值前验证码不得作废");
        assertNotNull(stringRedisTemplate.getExpire(failKey, TimeUnit.SECONDS));

        // 2. 第 5 次输错：达到失败上限，验证码立即作废（429 语义）
        codeDTO.setVerifyCode(wrongCodeOf(originalCode));
        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeDTO)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message")
                        .value("验证码错误次数过多，本次验证码已失效，请重新获取验证码"));
        assertEquals("5", stringRedisTemplate.opsForValue().get(failKey));
        assertNull(stringRedisTemplate.opsForValue().get(codeKey), "达到失败上限后验证码必须作废");

        // 3. 作废后即使提交正确的验证码也失败，且认证状态不得被写成 SUCCESS
        codeDTO.setVerifyCode(originalCode);
        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeDTO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("验证码已过期或未获取，请重新获取"));

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, TEST_USERNAME));
        StudentVerify stillPending = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1"));
        assertNotNull(stillPending);
        assertNotEquals("SUCCESS", stillPending.getVerifyStatus(),
                "验证码未核销成功时，认证状态绝不能变为 SUCCESS");

        // 4. 重新发送后可正常认证：失败计数被重置，新验证码生效（响应仍不回传验证码）
        submitVerifyExpectSuccess(userAccessToken, SCHOOL_EMAIL);
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(failKey)),
                "重新发送验证码必须重置失败计数");
        String resentCode = requireCachedCode(codeKey);

        codeDTO.setVerifyCode(resentCode);
        MvcResult verified = mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + userAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        assertTrue(objectMapper.readTree(verified.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data").isNull(), "核销成功响应也不得携带数据");

        // 校验数据库状态更新为 SUCCESS，且验证码明文不落库（MyBatis 参数日志会打印 SQL 实参）
        StudentVerify verifyRecord = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, user.getId())
                        .eq(StudentVerify::getVerifyStatus, "SUCCESS")
        );
        assertNotNull(verifyRecord, "认证状态应已更新为 SUCCESS");
        assertNotNull(verifyRecord.getVerifyTime(), "应记录认证通过时间");
        assertNull(verifyRecord.getVerifyCode(), "验证码明文绝不能写入数据库（否则会被 MyBatis 参数日志泄露）");

        // 核销后 Redis 中的验证码与失败计数都应清理
        assertNull(stringRedisTemplate.opsForValue().get(codeKey), "核销后 Redis 验证码应被清除");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(failKey)), "核销后失败计数应被清除");

        // 重新调用 /user/profile 验证返回认证学校与状态
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.schoolName").value("清华大学"));

        // 5. 用户维度限流：同一用户 10 分钟内第 4 次请求被拒绝（前三次已用掉配额）
        submitVerifyExpectSuccess(userAccessToken, SCHOOL_EMAIL);
        MvcResult userLimited = submitVerify(userAccessToken, SCHOOL_EMAIL);
        assertRateLimited(userLimited, "验证码请求过于频繁");
        String userLimitKey = RedisKeyConstants.studentVerifySendUserKey(userId);
        assertEquals("4", stringRedisTemplate.opsForValue().get(userLimitKey));
        Long userLimitTtl = stringRedisTemplate.getExpire(userLimitKey, TimeUnit.SECONDS);
        assertNotNull(userLimitTtl);
        assertTrue(userLimitTtl > 9 * 60 && userLimitTtl <= 10 * 60,
                "用户维度限流窗口应为 10 分钟，实际 TTL=" + userLimitTtl);

        System.out.printf("[学生认证限流] 用户维度超限响应: %s%n",
                userLimited.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // 6. 维度修正验证一：换一个新账号继续请求同一校园邮箱 —— 必须放行。
        //    这正是本次修复的攻击面：旧实现"只按邮箱计数"，任意账号都能把他人邮箱的当日额度打满，
        //    让被攻击者当天无法完成认证，同时给对方持续投递垃圾验证码邮件。
        //    新维度是"发起人 userId × 目标邮箱"，因此对方账号的配额与本人互不影响。
        String otherUsername = "stage1_other_" + UUID.randomUUID().toString().substring(0, 8);
        String otherToken = registerAndLogin(otherUsername, TestCredentials.randomPassword(),
                otherUsername + "@test.edu.cn");
        submitVerifyExpectSuccess(otherToken, SCHOOL_EMAIL);
        Long otherUserId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, otherUsername)).getId();
        assertEquals("1", stringRedisTemplate.opsForValue().get(
                        RedisKeyConstants.studentVerifySendUserEmailKey(otherUserId, SCHOOL_EMAIL)),
                "其他账号对同一校园邮箱的计数必须独立：首次下发后自己的计数为 1");

        // 6.1 核验失败计数同样是"发起人 × 邮箱"：他人输错验证码不会消耗本人的作废次数
        StudentVerifyCodeDTO otherCodeDTO = new StudentVerifyCodeDTO();
        otherCodeDTO.setSchoolEmail(SCHOOL_EMAIL);
        otherCodeDTO.setVerifyCode(wrongCodeOf(requireCachedCode(codeKey)));
        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherCodeDTO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("验证码错误，请重新输入"));
        assertEquals("1", stringRedisTemplate.opsForValue().get(
                        RedisKeyConstants.studentVerifyFailKey(otherUserId, SCHOOL_EMAIL)),
                "失败计数必须落在（其他账号 × 该邮箱）键上");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(failKey)),
                "他人输错验证码绝不能消耗本人（userId×邮箱）的失败次数");

        // 7. 维度修正验证二：本人清空 10 分钟窗口后继续请求同一邮箱 —— 24 小时配额仍然拦住
        stringRedisTemplate.delete(userLimitKey);
        assertEquals("3", stringRedisTemplate.opsForValue().get(userEmailLimitKey),
                "本人此前已对该邮箱占用 3 次 24 小时配额");
        MvcResult userEmailLimited = submitVerify(userAccessToken, SCHOOL_EMAIL);
        System.out.printf("[学生认证限流] 发起人×邮箱维度超限响应: %s（键 %s=%s）%n",
                userEmailLimited.getResponse().getContentAsString(StandardCharsets.UTF_8),
                userEmailLimitKey, stringRedisTemplate.opsForValue().get(userEmailLimitKey));
        assertRateLimited(userEmailLimited, "24 小时");
        assertEquals("4", stringRedisTemplate.opsForValue().get(userEmailLimitKey),
                "被拒请求同样计入 24 小时窗口（限流窗口内请求不会因为被拒而免费）");
        Long userEmailLimitTtl = stringRedisTemplate.getExpire(userEmailLimitKey, TimeUnit.SECONDS);
        assertNotNull(userEmailLimitTtl);
        assertTrue(userEmailLimitTtl > 23 * 3600 && userEmailLimitTtl <= 24 * 3600,
                "（发起人×邮箱）维度限流窗口应为 24 小时，实际 TTL=" + userEmailLimitTtl);
    }

    // =========================================================================
    // 校园认证测试辅助方法（非测试用例，不增加用例数量）
    // =========================================================================

    /**
     * 邮件通道策略断言（全部为组件级行为，不发真实邮件）：
     * <ul>
     *   <li>mail-enabled=false 且非 prod：验证码写入服务端日志（[DEV-ONLY]），不抛异常；</li>
     *   <li>mail-enabled=false 且 prod：拒绝降级，抛业务错误——验证码绝不允许写进生产日志；</li>
     *   <li>mail-enabled=true 但 SMTP 配置不完整 / SMTP 发送失败：一律抛明确业务错误，且错误信息中不含验证码；</li>
     *   <li>prod profile 关闭邮件通道或缺 SMTP 配置：启动期 fail-fast 拒绝启动（与 JWT 密钥 fail-fast 同风格）。</li>
     * </ul>
     */
    private void assertMailChannelPolicy() {
        String sampleCode = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));

        // 本地开发（mail-enabled=false 且非 prod）：走日志通道，不抛异常
        VerifyProperties devProperties = new VerifyProperties();
        VerifyCodeMailSender devSender = new VerifyCodeMailSender(
                devProperties, mailSenderProvider(null), new MockEnvironment());
        assertDoesNotThrow(() -> devSender.sendVerifyCode(SCHOOL_EMAIL, "清华大学", sampleCode),
                "本地开发（mail-enabled=false 且非 prod）必须走日志通道");

        // prod + mail-enabled=false：拒绝降级（否则验证码会落进生产日志）
        MockEnvironment prodEnvironment = new MockEnvironment();
        prodEnvironment.setActiveProfiles("prod");
        VerifyCodeMailSender prodSender = new VerifyCodeMailSender(
                devProperties, mailSenderProvider(null), prodEnvironment);
        BusinessException prodException = assertThrows(BusinessException.class,
                () -> prodSender.sendVerifyCode(SCHOOL_EMAIL, "清华大学", sampleCode));
        assertEquals(500, prodException.getCode());
        assertFalse(prodException.getMessage().contains(sampleCode), "错误信息中不得出现验证码");

        // mail-enabled=true 但 SMTP 配置不完整：明确业务错误，不降级返回验证码
        VerifyProperties incompleteProperties = configuredMailProperties();
        incompleteProperties.setMailPassword("");
        VerifyCodeMailSender incompleteSender = new VerifyCodeMailSender(
                incompleteProperties, mailSenderProvider(null), new MockEnvironment());
        BusinessException incompleteException = assertThrows(BusinessException.class,
                () -> incompleteSender.sendVerifyCode(SCHOOL_EMAIL, "清华大学", sampleCode));
        assertEquals(500, incompleteException.getCode());
        assertTrue(incompleteException.getMessage().contains("SMTP"),
                "提示必须指向 SMTP 配置问题: " + incompleteException.getMessage());
        assertFalse(incompleteException.getMessage().contains(sampleCode), "错误信息中不得出现验证码");

        // mail-enabled=true 且 SMTP 不可达：只抛业务错误，绝不降级
        JavaMailSender failingMailSender = mock(JavaMailSender.class);
        when(failingMailSender.createMimeMessage()).thenThrow(new MailSendException("SMTP 不可达（测试桩）"));
        VerifyCodeMailSender failingSender = new VerifyCodeMailSender(
                configuredMailProperties(), mailSenderProvider(failingMailSender), new MockEnvironment());
        BusinessException sendException = assertThrows(BusinessException.class,
                () -> failingSender.sendVerifyCode(SCHOOL_EMAIL, "清华大学", sampleCode));
        assertEquals(500, sendException.getCode());
        assertTrue(sendException.getMessage().contains("邮件发送失败"), sendException.getMessage());
        assertFalse(sendException.getMessage().contains(sampleCode), "发送失败不得降级为回传验证码");

        // prod 启动期守卫：关闭邮件通道 / 缺 SMTP 配置都必须拒绝启动，并给出中文环境变量清单
        VerifyProperties prodDisabled = new VerifyProperties();
        prodDisabled.setMailEnabled(false);
        IllegalStateException disabledException = assertThrows(IllegalStateException.class,
                () -> new VerifyMailProdGuard(prodDisabled));
        assertTrue(disabledException.getMessage().contains("mail-enabled"),
                "必须点明是 mail-enabled 导致拒绝启动: " + disabledException.getMessage());

        VerifyProperties prodMissingSmtp = new VerifyProperties();
        prodMissingSmtp.setMailEnabled(true);
        IllegalStateException missingSmtpException = assertThrows(IllegalStateException.class,
                () -> new VerifyMailProdGuard(prodMissingSmtp));
        assertTrue(missingSmtpException.getMessage().contains("MAIL_HOST"),
                "提示必须列出所需环境变量: " + missingSmtpException.getMessage());

        // 配置完整时守卫不得误报
        assertDoesNotThrow(() -> new VerifyMailProdGuard(configuredMailProperties()));

        // 日志掩码：只保留首尾字符与域名，日志里不出现完整校园邮箱
        assertEquals("t***t@mails.tsinghua.edu.cn",
                VerifyCodeMailSender.maskEmail("test_student@mails.tsinghua.edu.cn"));
        assertEquals("a***@test.edu.cn", VerifyCodeMailSender.maskEmail("ab@test.edu.cn"));
    }

    /**
     * 构造"配置完整"的邮件属性：主机名使用 RFC 2606 保留的 {@code .invalid} 顶级域（不可解析、
     * 不指向任何真实邮箱服务），口令运行时随机生成——源码中不出现任何可用凭据字面量。
     */
    private static VerifyProperties configuredMailProperties() {
        VerifyProperties properties = new VerifyProperties();
        properties.setMailEnabled(true);
        properties.setMailHost("smtp.campus-trade.invalid");
        properties.setMailPort(465);
        properties.setMailUsername("verify-mailbox");
        properties.setMailPassword(UUID.randomUUID().toString());
        properties.setMailFrom("verify-mailbox@campus-trade.invalid");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> mailSenderProvider(JavaMailSender mailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        return provider;
    }

    private MvcResult submitVerify(String token, String schoolEmail) throws Exception {
        StudentVerifyDTO dto = new StudentVerifyDTO();
        dto.setSchoolId(1L);
        dto.setStudentNumber("2026998877");
        dto.setSchoolEmail(schoolEmail);
        return mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andReturn();
    }

    private void submitVerifyExpectSuccess(String token, String schoolEmail) throws Exception {
        MvcResult result = submitVerify(token, schoolEmail);
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, root.path("code").asInt(), "验证码应下发成功: " + root.path("message").asText());
        assertTrue(root.get("data").isNull(), "响应 data 必须为空");
    }

    private void assertRateLimited(MvcResult result, String expectedMessageFragment) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(429, root.path("code").asInt(), "超限必须返回 429 语义: " + root.path("message").asText());
        assertTrue(root.path("message").asText().contains(expectedMessageFragment),
                "限流提示应包含[" + expectedMessageFragment + "]: " + root.path("message").asText());
        assertTrue(root.get("data").isNull(), "限流响应不得携带数据");
    }

    /** 读取 Redis 中的验证码，不存在时直接失败（避免后续断言用 null 静默通过）。 */
    private String requireCachedCode(String codeKey) {
        String code = stringRedisTemplate.opsForValue().get(codeKey);
        assertNotNull(code, "Redis 中应存在验证码: " + codeKey);
        return code;
    }

    /** 生成一个与正确验证码不同的 6 位数字，用于构造"输错"场景。 */
    private static String wrongCodeOf(String correctCode) {
        return String.format("%06d", (Integer.parseInt(correctCode) + 1) % 1000000);
    }

    private String registerAndLogin(String username, String password, String email) throws Exception {
        RegisterRequestDTO register = new RegisterRequestDTO();
        register.setUsername(username);
        register.setPassword(password);
        register.setEmail(email);
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        LoginRequestDTO login = new LoginRequestDTO();
        login.setUsername(username);
        login.setPassword(password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("accessToken").asText();
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
        Boolean hasBlacklist = stringRedisTemplate.hasKey(RedisKeyConstants.jwtBlacklistKey(userAccessToken));
        assertTrue(Boolean.TRUE.equals(hasBlacklist), "登出后 Token 应被写入 Redis 黑名单");

        // 再次使用该 Token 请求应被拒绝 (401)
        mockMvc.perform(get("/user/profile")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
