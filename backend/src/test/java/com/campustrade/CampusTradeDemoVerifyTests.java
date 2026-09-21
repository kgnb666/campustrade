package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.config.VerifyDemoGuard;
import com.campustrade.config.VerifyProperties;
import com.campustrade.dto.StudentVerifyCodeDTO;
import com.campustrade.dto.StudentVerifyDTO;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.StudentVerifyService;
import com.campustrade.service.mail.VerifyCodeMailSender;
import com.campustrade.support.TestCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校园认证"演示模式"的回归测试。
 *
 * <h2>为什么需要它</h2>
 * 个人开发者没有学校邮箱，无法演示"申请验证码 → 输码 → 认证成功"。演示模式让<b>白名单内</b>
 * 的邮箱不发真实邮件、验证码随响应返回。它天生是一个"放松"项，所以本类既要证明它<b>能用</b>
 * （闭环真的走通），也要证明它<b>没有把放松扩到白名单之外</b>（其余邮箱仍然 data 为 null、
 * 仍然不发验证码）。这两件事缺一不可：只测前者，一个"忘了判断白名单"的改动会悄悄让全站
 * 变成零成本自证。
 *
 * <h2>为什么用 @DynamicPropertySource 生成随机演示邮箱</h2>
 * 演示邮箱是配置项（{@code verify.demo-emails}），而 JUnit 的静态属性源比实例字段更早求值，
 * 因此这里用一个带随机后缀的静态常量，既让本类可以反复运行（不会因为"该校园邮箱已被其他
 * 账号完成认证"的部分唯一索引而失败），也不会与其它测试类抢同一个邮箱。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampusTradeDemoVerifyTests {

    /** 演示白名单里唯一的邮箱：清华后缀，带随机后缀以便反复运行 */
    private static final String DEMO_EMAIL = "demo-" + UUID.randomUUID().toString().substring(0, 8)
            + "@mails.tsinghua.edu.cn";

    /** 同一个学校、但**不在**白名单里的邮箱：用来证明放松没有外溢 */
    private static final String NORMAL_EMAIL = "normal-" + UUID.randomUUID().toString().substring(0, 8)
            + "@mails.tsinghua.edu.cn";

    private static final Long DEMO_USER_ID = 77880101L;
    private static final String DEMO_USERNAME = "demo_verify_user";

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
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void demoProperties(DynamicPropertyRegistry registry) {
        registry.add("verify.demo-mode-enabled", () -> true);
        registry.add("verify.demo-emails", () -> DEMO_EMAIL);
    }

    @BeforeEach
    void setUp() {
        ensureUser();
        // 每个用例重置认证记录与 Redis 计数，避免用例之间互相影响
        // （部分唯一索引只有在 verify_status=SUCCESS 时才生效，重复演示必须先把上一轮清掉）
        resetVerifyState();
    }

    // =========================================================================
    // 1. 演示闭环：白名单邮箱 → 验证码随响应返回 → 输码即认证成功
    // =========================================================================

    @Test
    @DisplayName("1. 演示模式闭环：白名单邮箱的验证码随响应返回，且能凭该验证码完成认证")
    void testDemoModeRoundTrip() throws Exception {
        String token = demoUserToken();

        MvcResult sent = mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoRequest(DEMO_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(StudentVerifyService.VERIFY_CODE_SENT_MESSAGE))
                .andExpect(jsonPath("$.data.demoMode").value(true))
                .andReturn();

        JsonNode data = objectMapper.readTree(
                sent.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        String demoCode = data.get("demoCode").asText();
        assertTrue(demoCode.matches("\\d{6}"), "演示验证码必须是 6 位数字，实际: " + demoCode);

        // 验证码仍必须落进 Redis：认证的核销逻辑不改，演示模式只改变"谁拿到验证码"
        String cached = stringRedisTemplate.opsForValue().get(RedisKeyConstants.studentVerifyKey(DEMO_EMAIL));
        assertEquals(demoCode, cached, "演示通道下验证码同样要写入 Redis，核销走同一条路径");

        // 凭响应里的验证码核销
        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeRequest(DEMO_EMAIL, demoCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(StudentVerifyService.VERIFY_SUCCESS_MESSAGE));

        StudentVerify record = studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, DEMO_USER_ID)
                        .eq(StudentVerify::getSchoolEmail, DEMO_EMAIL)
                        .last("LIMIT 1"));
        assertNotNull(record, "认证记录必须存在");
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), record.getVerifyStatus(),
                "演示模式下走完闭环后认证状态必须是 SUCCESS");
        assertNotNull(record.getVerifyTime(), "认证成功时间必须写入");
    }

    // =========================================================================
    // 2. 白名单之外：接口契约一个字节都不变
    // =========================================================================

    @Test
    @DisplayName("2. 同一学校但不在白名单的邮箱：即使演示模式已开启，响应 data 仍为 null")
    void testNonWhitelistedEmailKeepsContract() throws Exception {
        String token = demoUserToken();

        MvcResult result = mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoRequest(NORMAL_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(body);
        assertTrue(root.get("data").isNull(),
                "演示模式只对白名单邮箱生效，其它邮箱必须保持'验证码不随响应返回'的既有契约");

        // 响应里不得出现该邮箱对应的验证码明文
        String cached = stringRedisTemplate.opsForValue().get(RedisKeyConstants.studentVerifyKey(NORMAL_EMAIL));
        assertNotNull(cached, "非白名单邮箱仍要正常生成验证码");
        assertFalse(body.contains(cached), "非白名单邮箱的验证码绝不能出现在响应体里");
    }

    // =========================================================================
    // 3. 通道选择：演示分支必须优先，且绝不碰邮件发送器
    // =========================================================================

    @Test
    @DisplayName("3. 演示通道不发邮件：白名单邮箱返回 DEMO 且完全不触碰 JavaMailSender")
    void testDemoChannelSkipsMailSending() {
        VerifyProperties properties = configuredMailProperties();
        properties.setDemoModeEnabled(true);
        properties.setDemoEmails(List.of(DEMO_EMAIL));

        // SMTP 直接不可用：若演示分支没有优先命中，这里必然抛业务错误
        JavaMailSender explodingSender = mock(JavaMailSender.class);
        when(explodingSender.createMimeMessage())
                .thenThrow(new MailSendException("SMTP 不可达（测试桩）"));

        VerifyCodeMailSender sender = new VerifyCodeMailSender(
                properties, provider(explodingSender), new MockEnvironment());

        assertEquals(VerifyCodeMailSender.Channel.DEMO,
                sender.sendVerifyCode(DEMO_EMAIL, "清华大学", "123456"),
                "白名单邮箱必须走演示通道（此时连 SMTP 都不该被碰）");
        verifyNoInteractions(explodingSender);
    }

    @Test
    @DisplayName("4. 白名单之外仍按原规则下发：邮件通道开启时发送失败照样抛错，不降级返回验证码")
    void testNonWhitelistedEmailStillUsesMailChannel() {
        VerifyProperties properties = configuredMailProperties();
        properties.setDemoModeEnabled(true);
        properties.setDemoEmails(List.of(DEMO_EMAIL));

        JavaMailSender explodingSender = mock(JavaMailSender.class);
        when(explodingSender.createMimeMessage())
                .thenThrow(new MailSendException("SMTP 不可达（测试桩）"));

        VerifyCodeMailSender sender = new VerifyCodeMailSender(
                properties, provider(explodingSender), new MockEnvironment());

        BusinessException e = assertThrows(BusinessException.class,
                () -> sender.sendVerifyCode(NORMAL_EMAIL, "清华大学", "123456"),
                "非白名单邮箱必须继续走邮件通道：发送失败就报错，绝不降级为返回验证码");
        assertEquals(500, e.getCode());
        assertFalse(e.getMessage().contains("123456"), "错误信息中不得出现验证码");

        // 邮件通道关闭时仍是本地开发日志通道（未被演示分支误伤）
        VerifyProperties devProperties = new VerifyProperties();
        devProperties.setDemoModeEnabled(true);
        devProperties.setDemoEmails(List.of(DEMO_EMAIL));
        VerifyCodeMailSender devSender = new VerifyCodeMailSender(
                devProperties, provider(null), new MockEnvironment());
        assertEquals(VerifyCodeMailSender.Channel.DEV_LOG,
                devSender.sendVerifyCode(NORMAL_EMAIL, "清华大学", "123456"));
    }

    // =========================================================================
    // 5. 演示必须"能反复演"：限流与"已认证不可重复"两条都不该拦住演示
    // =========================================================================

    @Test
    @DisplayName("7. 同一个演示邮箱可以不限次数反复演示：第二轮照样能拿到码并核销成功")
    void testDemoCanBeRepeated() throws Exception {
        String token = demoUserToken();

        // 第一轮：申请 → 核销 → SUCCESS
        String firstCode = submitAndExtractDemoCode(token, DEMO_EMAIL);
        verifyWithCode(token, DEMO_EMAIL, firstCode, 200);
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), currentStatus(DEMO_EMAIL),
                "第一轮结束应为已认证");

        // 第二轮：同一个账号 + 同一个邮箱。这里有两道原本会拦住演示的坎：
        //   ① submitVerify 原本对 SUCCESS 行不做任何改写 → 核销时必然 409「已完成认证，无需重复核销」；
        //   ② 限流本会在第 4 次请求时给出 429「请求过于频繁」。
        // 演示通道对白名单邮箱把这两道都放开，且放开只针对白名单邮箱（见用例 8/9）。
        String secondCode = submitAndExtractDemoCode(token, DEMO_EMAIL);
        assertEquals(StudentVerifyStatus.PENDING.getCode(), currentStatus(DEMO_EMAIL),
                "第二轮申请应把演示邮箱重置为待认证，这样页面才能重新演示一次状态跃迁");
        verifyWithCode(token, DEMO_EMAIL, secondCode, 200);
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), currentStatus(DEMO_EMAIL),
                "第二轮核销后应重新变成已认证");
    }

    @Test
    @DisplayName("8. 演示邮箱不受发送限流；非白名单邮箱照旧在第 4 次被限流拦下")
    void testDemoEmailBypassesSendLimits() throws Exception {
        String token = demoUserToken();

        // 白名单邮箱连打 4 次（阈值是 10 分钟 3 次）：全部成功，且用户维度计数根本没有增加
        for (int i = 1; i <= 4; i++) {
            submitAndExtractDemoCode(token, DEMO_EMAIL);
        }
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                        RedisKeyConstants.studentVerifySendUserKey(DEMO_USER_ID))),
                "演示通道不发信，限流计数器不应被它增加（否则演示几次就把自己的额度用光了）");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                        RedisKeyConstants.studentVerifySendUserEmailKey(DEMO_USER_ID, DEMO_EMAIL))));

        // 非白名单邮箱：第 4 次必须被用户维度限流挡下（证明放开只对白名单生效）
        for (int i = 1; i <= 3; i++) {
            submitExpectingOk(token, NORMAL_EMAIL);
        }
        mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoRequest(NORMAL_EMAIL))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("请求过于频繁")));
    }

    @Test
    @DisplayName("9. 非白名单邮箱不允许被申请动作回退：已认证后重复核销仍是 409，认证时间不被抹掉")
    void testNonWhitelistedEmailCannotBeResetByResubmit() throws Exception {
        String token = demoUserToken();

        submitExpectingOk(token, NORMAL_EMAIL);
        String code = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.studentVerifyKey(NORMAL_EMAIL));
        assertNotNull(code, "非白名单邮箱的验证码在 Redis 里（不随响应返回）");
        verifyWithCode(token, NORMAL_EMAIL, code, 200);

        StudentVerify afterFirst = selectVerify(NORMAL_EMAIL);
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), afterFirst.getVerifyStatus());
        LocalDateTime firstVerifyTime = afterFirst.getVerifyTime();
        assertNotNull(firstVerifyTime);

        // 再申请一次：非白名单邮箱不得被重置回 PENDING（SUCCESS 行只能由核销写入，不能被申请动作回退）
        submitExpectingOk(token, NORMAL_EMAIL);
        StudentVerify afterResubmit = selectVerify(NORMAL_EMAIL);
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), afterResubmit.getVerifyStatus(),
                "非白名单邮箱重复申请后仍必须是已认证：放松只对配置点名的演示邮箱生效");
        assertEquals(firstVerifyTime, afterResubmit.getVerifyTime(), "认证时间不得被申请动作改写");

        // 用新验证码再核销一次：必须是 409「已完成认证」，而不是悄悄成功
        String newCode = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.studentVerifyKey(NORMAL_EMAIL));
        verifyWithCode(token, NORMAL_EMAIL, newCode, 409);
    }

    // =========================================================================
    // 6. 判定与护栏：白名单为空 = 未开启；开关开着却没白名单 = 拒绝启动
    // =========================================================================
    @Test
    @DisplayName("5. 判定规则：白名单为空视为未开启；命中判定对大小写与首尾空白不敏感")
    void testDemoEmailJudgement() {
        VerifyProperties properties = new VerifyProperties();

        // 开关关着：即使白名单非空，任何邮箱都不是演示邮箱
        properties.setDemoEmails(List.of("demo@x.edu.cn"));
        assertFalse(properties.isDemoModeActive(), "开关关闭时演示模式必须未生效");
        assertFalse(properties.isDemoEmail("demo@x.edu.cn"));

        // 开关开着但白名单为空（空列表 / 全空白 / null）：视为未开启，而不是"放行所有邮箱"
        properties.setDemoModeEnabled(true);
        properties.setDemoEmails(List.of());
        assertFalse(properties.isDemoModeActive(), "白名单为空时不得视为已开启");
        assertFalse(properties.isDemoEmail("demo@x.edu.cn"), "白名单为空时不得命中任何邮箱");
        properties.setDemoEmails(List.of("", "   "));
        assertFalse(properties.isDemoModeActive(), "全空白的白名单同样不得视为已开启");
        properties.setDemoEmails(null);
        assertFalse(properties.isDemoModeActive(), "白名单为 null（未配置）时不得视为已开启");
        assertFalse(properties.isDemoEmail(""), "空邮箱永远不是演示邮箱");
        assertFalse(properties.isDemoEmail(null), "null 邮箱永远不是演示邮箱");

        // 正常命中 + 归一化
        properties.setDemoEmails(List.of("  Demo@X.edu.CN  "));
        assertTrue(properties.isDemoModeActive());
        assertTrue(properties.isDemoEmail("demo@x.edu.cn"), "归一化后必须大小写不敏感");
        assertTrue(properties.isDemoEmail("  DEMO@X.EDU.CN "), "归一化后必须去首尾空白");
        assertFalse(properties.isDemoEmail("demo2@x.edu.cn"), "不在白名单里的邮箱不得命中");
    }

    @Test
    @DisplayName("6. 启动期护栏：演示开关打开但白名单为空时拒绝启动，配置齐全时正常放行")
    void testDemoGuardFailFast() {
        VerifyProperties broken = new VerifyProperties();
        broken.setDemoModeEnabled(true);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new VerifyDemoGuard(broken),
                "开启演示模式却忘记配置白名单几乎总是配置事故，必须在启动期就说清楚");
        assertTrue(e.getMessage().contains("VERIFY_DEMO_EMAILS"), e.getMessage());

        VerifyProperties blank = new VerifyProperties();
        blank.setDemoModeEnabled(true);
        blank.setDemoEmails(List.of(" ", ""));
        assertThrows(IllegalStateException.class, () -> new VerifyDemoGuard(blank));

        // 关闭（默认）与"开着且有白名单"都不应拦住启动
        assertDoesNotThrow(() -> new VerifyDemoGuard(new VerifyProperties()));
        VerifyProperties ok = new VerifyProperties();
        ok.setDemoModeEnabled(true);
        ok.setDemoEmails(List.of(DEMO_EMAIL));
        assertDoesNotThrow(() -> new VerifyDemoGuard(ok));
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private String demoUserToken() {
        return jwtTokenProvider.generateAccessToken(DEMO_USER_ID, DEMO_USERNAME, "USER");
    }

    /** 申请验证码并把响应里的演示验证码取出来（仅演示通道会把码带回来） */
    private String submitAndExtractDemoCode(String token, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoRequest(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertNotNull(data, "演示通道下 data 必须携带验证码");
        assertTrue(data.get("demoMode").asBoolean(), "演示通道下 demoMode 必须为 true");
        String code = data.get("demoCode").asText();
        assertTrue(code.matches("\\d{6}"), "演示验证码必须是 6 位数字，实际: " + code);
        return code;
    }

    /** 申请验证码并期望成功（用于非白名单邮箱——它的码不随响应返回，需自行从 Redis 取） */
    private void submitExpectingOk(String token, String email) throws Exception {
        mockMvc.perform(post("/student/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(demoRequest(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void verifyWithCode(String token, String email, String code, int expectedHttpStatus) throws Exception {
        mockMvc.perform(post("/student/verify/code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(codeRequest(email, code))))
                .andExpect(status().is(expectedHttpStatus));
    }

    private StudentVerify selectVerify(String email) {
        return studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, DEMO_USER_ID)
                        .eq(StudentVerify::getSchoolEmail, email)
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1"));
    }

    private String currentStatus(String email) {
        StudentVerify record = selectVerify(email);
        assertNotNull(record, "认证记录必须存在: " + email);
        return record.getVerifyStatus();
    }

    private StudentVerifyDTO demoRequest(String email) {
        StudentVerifyDTO dto = new StudentVerifyDTO();
        dto.setSchoolId(1L); // 清华大学 @mails.tsinghua.edu.cn
        dto.setStudentNumber("2026998877");
        dto.setSchoolEmail(email);
        return dto;
    }

    private StudentVerifyCodeDTO codeRequest(String email, String code) {
        StudentVerifyCodeDTO dto = new StudentVerifyCodeDTO();
        dto.setSchoolEmail(email);
        dto.setVerifyCode(code);
        return dto;
    }

    /** 建库内用户（与注册落库字段一致），避免占用"单 IP 每小时 20 次"的注册额度 */
    private void ensureUser() {
        if (userMapper.selectById(DEMO_USER_ID) != null) {
            return;
        }
        String randomPassword = TestCredentials.randomPassword();
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(User.builder()
                .id(DEMO_USER_ID)
                .username(DEMO_USERNAME)
                .password(passwordEncoder.encode(randomPassword))
                .nickname("演示认证用户")
                .email(DEMO_USERNAME + "@example.com")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build());
        userCreditMapper.insertCreditIfAbsent(IdWorker.getId(), DEMO_USER_ID);
    }

    private void resetVerifyState() {
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, DEMO_USER_ID));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyKey(DEMO_EMAIL));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyKey(NORMAL_EMAIL));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyFailKey(DEMO_USER_ID, DEMO_EMAIL));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifySendUserEmailKey(DEMO_USER_ID, DEMO_EMAIL));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifySendUserEmailKey(DEMO_USER_ID, NORMAL_EMAIL));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifySendUserKey(DEMO_USER_ID));
    }

    /** mail-enabled=true 且 SMTP 四项齐全的配置（发送环节仍由测试桩决定成败） */
    private VerifyProperties configuredMailProperties() {
        VerifyProperties properties = new VerifyProperties();
        properties.setMailEnabled(true);
        properties.setMailHost("smtp.example.com");
        properties.setMailUsername("no-reply@example.com");
        properties.setMailPassword("stub-password-not-a-real-credential");
        properties.setMailFrom("no-reply@example.com");
        return properties;
    }

    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }
}
