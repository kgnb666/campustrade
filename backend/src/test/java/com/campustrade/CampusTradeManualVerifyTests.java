package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.ManualVerifyRequest;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.enums.StudentVerifyStatus;
import com.campustrade.enums.VerifyMethod;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AdminVerifyService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「无邮箱通道」校园认证的回归测试（学生证照片 + 管理员人工审核）。
 *
 * <h2>这条通道为什么必须有自己的测试</h2>
 * 它存在的理由是"部分高校没有学生邮箱"——也就是说，它的目标用户**无法**用邮箱通道自证，
 * 因此这条通道一旦坏了，那批学生就彻底无法发布商品（认证是发布商品的硬前置）。
 * 同时它是唯一一条"认证结论由人给出"的路径，规则更细：驳回必须写原因、
 * 未处理的申请不能被重复处置、被驳回的学生要能改材料重来、学号不能一码多绑。
 * 本类把这些规则逐条钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampusTradeManualVerifyTests {

    /** 每次运行用不同的学号，避免与其它测试类或历史数据撞唯一索引 */
    private static final String STUDENT_NUMBER =
            "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final Long SCHOOL_ID = 1L;         // 清华大学
    private static final Long APPLICANT_ID = 77890101L;
    private static final String APPLICANT = "manual_applicant";
    private static final Long OTHER_ID = 77890102L;
    private static final String OTHER = "manual_other";
    private static final Long ADMIN_ID = 77890900L;
    private static final String ADMIN = "manual_admin";
    private static final String EVIDENCE_URL = "http://cdn.example.com/evidence/student-card.png";

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

    @BeforeEach
    void setUp() {
        ensureUser(APPLICANT_ID, APPLICANT, "USER");
        ensureUser(OTHER_ID, OTHER, "USER");
        ensureUser(ADMIN_ID, ADMIN, "ADMIN");
        resetState();
    }

    @AfterEach
    void tearDown() {
        resetState();
    }

    // =========================================================================
    // 1. 提交：落库为 PENDING + MANUAL，材料与状态可被本人查询
    // =========================================================================

    @Test
    @DisplayName("1. 提交学生证材料 → 记录为 PENDING/MANUAL，且状态查询能读到待审核")
    void testSubmitManualVerify() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");

        StudentVerify record = applicantRecord();
        assertNotNull(record, "提交后必须有一条认证记录");
        assertEquals(StudentVerifyStatus.PENDING.getCode(), record.getVerifyStatus());
        assertEquals(VerifyMethod.MANUAL.getCode(), record.getVerifyMethod(),
                "人工通道必须记录通道类型，否则审核队列无法区分两条通道的申请");
        assertEquals(EVIDENCE_URL, record.getEvidenceUrl(), "材料地址必须落库");
        assertNull(record.getSchoolEmail(), "人工通道不写校园邮箱：没有邮箱正是走这条通道的原因");

        MvcResult status = mockMvc.perform(get("/student/verify/status")
                        .header("Authorization", "Bearer " + applicantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.verifyMethod").value("MANUAL"))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andReturn();
        assertTrue(status.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(STUDENT_NUMBER),
                "状态查询必须回显学号，便于学生核对自己提交的内容");
    }

    @Test
    @DisplayName("2. 重复提交＝覆盖材料并保持待审核（学生改错了可以立刻重交，不会造出第二条记录）")
    void testResubmitOverwrites() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三丰");

        Long count = studentVerifyMapper.selectCount(
                new LambdaQueryWrapper<StudentVerify>().eq(StudentVerify::getUserId, APPLICANT_ID));
        assertEquals(1L, count, "同一用户只应有一条认证记录（两条通道共用同一行）");
        assertEquals("张三丰", applicantRecord().getRealName(), "重新提交必须覆盖姓名等材料");
    }

    // =========================================================================
    // 2. 审核：权限、驳回必填原因、通过后生效
    // =========================================================================

    @Test
    @DisplayName("3. 非管理员不能访问审核队列与处置接口（403）")
    void testAdminOnly() throws Exception {
        String token = applicantToken();
        submitExpectOk(token, STUDENT_NUMBER, "张三");
        Long verifyId = applicantRecord().getId();

        mockMvc.perform(get("/admin/verifies").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", "自己批自己")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("4. 驳回必须写原因；写了原因才落 REJECTED，学生能看到原因并重新提交")
    void testRejectRequiresReason() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");
        Long verifyId = applicantRecord().getId();

        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("REJECT", "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("原因")));

        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("REJECT", "照片模糊，请重新上传学生证内页")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("REJECTED"));

        mockMvc.perform(get("/student/verify/status")
                        .header("Authorization", "Bearer " + applicantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewNote").value("照片模糊，请重新上传学生证内页"))
                .andExpect(jsonPath("$.data.verified").value(false));

        // 重新提交后回到待审核，且上一轮的驳回原因必须被清掉（否则"待审核"还挂着旧结论）
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");
        StudentVerify resubmitted = applicantRecord();
        assertEquals(StudentVerifyStatus.PENDING.getCode(), resubmitted.getVerifyStatus());
        assertNull(resubmitted.getReviewNote(), "重新提交必须清空上一轮审核结论");
        assertNull(resubmitted.getReviewerId(), "重新提交必须清空上一轮审核人");
    }

    @Test
    @DisplayName("5. 管理员通过 → SUCCESS + 认证时间；同一申请不能重复处置（409）")
    void testApproveMakesVerified() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");
        Long verifyId = applicantRecord().getId();

        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", "已电话核实")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifyStatus").value("SUCCESS"));

        StudentVerify approved = studentVerifyMapper.selectById(verifyId);
        assertEquals(StudentVerifyStatus.SUCCESS.getCode(), approved.getVerifyStatus());
        assertNotNull(approved.getVerifyTime(), "通过必须写入认证时间（下游按它展示认证状态）");
        assertEquals(ADMIN_ID, approved.getReviewerId(), "审核人必须留痕");

        mockMvc.perform(get("/student/verify/status")
                        .header("Authorization", "Bearer " + applicantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        // 再处置一次：状态机不允许（PENDING 才可处置）
        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("REJECT", "反悔了")))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // 3. 学号唯一与通道边界
    // =========================================================================

    @Test
    @DisplayName("6. 同一学号不能被第二个账号认证：提交时即被拒（409）")
    void testStudentNumberOccupied() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");
        Long verifyId = applicantRecord().getId();
        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", "通过")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/student/verify/manual")
                        .header("Authorization", "Bearer " + otherToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manualRequest(STUDENT_NUMBER, "李四"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("学号")));
    }

    @Test
    @DisplayName("7. 邮箱通道的申请不能被人工审核接口处置（避免两条通道的状态机互相污染）")
    void testEmailChannelRecordRejectedByAdminReview() throws Exception {
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(APPLICANT_ID)
                .schoolId(SCHOOL_ID)
                .studentNumber(STUDENT_NUMBER)
                .schoolEmail("someone@mails.tsinghua.edu.cn")
                .verifyMethod(VerifyMethod.EMAIL.getCode())
                .verifyStatus(StudentVerifyStatus.PENDING.getCode())
                .createdTime(LocalDateTime.now())
                .build());
        Long verifyId = applicantRecord().getId();

        mockMvc.perform(put("/admin/verifies/" + verifyId + "/review")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody("APPROVE", "通过")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("无邮箱通道")));
    }

    @Test
    @DisplayName("8. 审核队列按待审核筛选，并能看到材料地址（审核人不必再点进详情）")
    void testReviewQueue() throws Exception {
        submitExpectOk(applicantToken(), STUDENT_NUMBER, "张三");

        mockMvc.perform(get("/admin/verifies").param("status", "PENDING").param("size", "50")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].evidenceUrl").value(EVIDENCE_URL))
                .andExpect(jsonPath("$.data.records[0].realName").value("张三"))
                .andExpect(jsonPath("$.data.records[0].verifyStatusDesc").value("待核销/待审核"));
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    private String applicantToken() {
        return jwtTokenProvider.generateAccessToken(APPLICANT_ID, APPLICANT, "USER");
    }

    private String otherToken() {
        return jwtTokenProvider.generateAccessToken(OTHER_ID, OTHER, "USER");
    }

    private String adminToken() {
        return jwtTokenProvider.generateAccessToken(ADMIN_ID, ADMIN, "ADMIN");
    }

    private ManualVerifyRequest manualRequest(String studentNumber, String realName) {
        ManualVerifyRequest request = new ManualVerifyRequest();
        request.setSchoolId(SCHOOL_ID);
        request.setStudentNumber(studentNumber);
        request.setRealName(realName);
        request.setEvidenceUrl(EVIDENCE_URL);
        return request;
    }

    private String reviewBody(String action, String note) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("action", action, "note", note));
    }

    private void submitExpectOk(String token, String studentNumber, String realName) throws Exception {
        mockMvc.perform(post("/student/verify/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manualRequest(studentNumber, realName))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(StudentVerifyService.MANUAL_SUBMIT_MESSAGE));
    }

    private StudentVerify applicantRecord() {
        return studentVerifyMapper.selectOne(
                new LambdaQueryWrapper<StudentVerify>()
                        .eq(StudentVerify::getUserId, APPLICANT_ID)
                        .orderByDesc(StudentVerify::getCreatedTime)
                        .last("LIMIT 1"));
    }

    private void ensureUser(Long id, String username, String role) {
        if (userMapper.selectById(id) != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(User.builder()
                .id(id)
                .username(username)
                .password(passwordEncoder.encode(TestCredentials.randomPassword()))
                .nickname(username)
                .email(username + "@example.com")
                .role(role)
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build());
        userCreditMapper.insertCreditIfAbsent(IdWorker.getId(), id);
    }

    private void resetState() {
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, APPLICANT_ID));
        studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>()
                .eq(StudentVerify::getUserId, OTHER_ID));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyManualUserKey(APPLICANT_ID));
        stringRedisTemplate.delete(RedisKeyConstants.studentVerifyManualUserKey(OTHER_ID));
    }
}
