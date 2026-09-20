package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.order.CreateOrderRequest;
import com.campustrade.dto.report.CreateReportRequest;
import com.campustrade.entity.CampusSchool;
import com.campustrade.entity.Category;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.CampusSchoolMapper;
import com.campustrade.mapper.CategoryMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.FileService;
import com.campustrade.service.GoodsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage Fix-2: 后端工程质量与高可用加固专项自动化测试套件
 * 重点覆盖：
 * 1. 【SEC-03】图片上传基于文件头 (Magic Bytes) 的二进制强校验，拦截伪装脚本；
 * 2. 【SEC-03】合法 JPEG / PNG / WebP 真实二进制流成功上传并生成 MinIO 访问 URL；
 * 3. 【BIZ-02】浏览商品详情自动将 goodsId 注册进 Redis Set goods:views:dirty_ids；
 * 4. 【BIZ-02】syncViewCounts() 批量从 Set 弹出脏 ID 进行 DB 落盘与原子扣减，无 keys(*) 全库扫描；
 * 5. 【ARCH-03】各 Controller 路由统一标准规范；
 * 6. 【@CurrentUser】参数解析器正确从 Security 上下文解析登录 User 实体并注入 Controller。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CampusTradeFixStage2Tests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FileService fileService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private CampusSchoolMapper campusSchoolMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_USER = "fix2_user_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String SELLER_USER = "fix2_seller_" + UUID.randomUUID().toString().substring(0, 8);
    private static User testUserEntity;
    private static User sellerUserEntity;
    private static String testToken;
    private static Long testGoodsId;

    @BeforeAll
    static void initTestData(@Autowired UserMapper userMapper,
                             @Autowired StudentVerifyMapper studentVerifyMapper,
                             @Autowired CampusSchoolMapper campusSchoolMapper,
                             @Autowired CategoryMapper categoryMapper,
                             @Autowired GoodsMapper goodsMapper,
                             @Autowired JwtTokenProvider jwtTokenProvider) {
        // 1. 初始化学校与分类
        CampusSchool school = campusSchoolMapper.selectById(1L);
        if (school == null) {
            school = CampusSchool.builder()
                    .id(1L)
                    .schoolName("清华大学")
                    .schoolCode("THU")
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .build();
            campusSchoolMapper.insert(school);
        }

        Category cat = categoryMapper.selectById(101L);
        if (cat == null) {
            cat = Category.builder()
                    .id(101L)
                    .name("数码数码")
                    .icon("digital.png")
                    .sort(1)
                    .status(1)
                    .createdTime(LocalDateTime.now())
                    .build();
            categoryMapper.insert(cat);
        }

        // 2. 创建并认证测试用户 (举报人)
        LocalDateTime now = LocalDateTime.now();
        testUserEntity = User.builder()
                .username(TEST_USER)
                .password("$2a$10$abcdefghijklmnopqrstuvwxyz123456")
                .nickname("Fix2加固用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(testUserEntity);

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(testUserEntity.getId())
                .schoolId(1L)
                .studentNumber("STU_FIX2_" + testUserEntity.getId())
                .schoolEmail(TEST_USER + "@mails.tsinghua.edu.cn")
                .verifyStatus("SUCCESS")
                .verifyTime(now)
                .createdTime(now)
                .build());

        testToken = jwtTokenProvider.generateAccessToken(testUserEntity.getId(), testUserEntity.getUsername(), testUserEntity.getRole());

        // 3. 创建卖家用户 (被举报商品的发布者)
        sellerUserEntity = User.builder()
                .username(SELLER_USER)
                .password("$2a$10$abcdefghijklmnopqrstuvwxyz123456")
                .nickname("Fix2卖家用户")
                .role("USER")
                .status("ACTIVE")
                .createdTime(now)
                .updatedTime(now)
                .build();
        userMapper.insert(sellerUserEntity);

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(sellerUserEntity.getId())
                .schoolId(1L)
                .studentNumber("STU_FIX2_S_" + sellerUserEntity.getId())
                .schoolEmail(SELLER_USER + "@mails.tsinghua.edu.cn")
                .verifyStatus("SUCCESS")
                .verifyTime(now)
                .createdTime(now)
                .build());

        // 4. 创建测试商品
        Goods goods = Goods.builder()
                .sellerId(sellerUserEntity.getId())
                .schoolId(1L)
                .categoryId(101L)
                .title("Fix2测试商品 " + TEST_USER)
                .description("高可用与工程规范加固专用测试商品")
                .price(new BigDecimal("99.00"))
                .originalPrice(new BigDecimal("199.00"))
                .conditionLevel("95新")
                .status("ON_SALE")
                .location("紫荆公寓")
                .viewCount(20)
                .createdTime(now)
                .updatedTime(now)
                .build();
        goodsMapper.insert(goods);
        testGoodsId = goods.getId();
    }

    @AfterAll
    static void cleanup(@Autowired UserMapper userMapper,
                        @Autowired GoodsMapper goodsMapper,
                        @Autowired StudentVerifyMapper studentVerifyMapper,
                        @Autowired ReportMapper reportMapper,
                        @Autowired StringRedisTemplate stringRedisTemplate) {
        if (testGoodsId != null) {
            reportMapper.delete(new LambdaQueryWrapper<Report>().eq(Report::getTargetId, testGoodsId));
            goodsMapper.deleteById(testGoodsId);
            stringRedisTemplate.delete(RedisKeyConstants.goodsViewKey(testGoodsId));
        }
        if (testUserEntity != null) {
            userMapper.deleteById(testUserEntity.getId());
            studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>().eq(StudentVerify::getUserId, testUserEntity.getId()));
        }
        if (sellerUserEntity != null) {
            userMapper.deleteById(sellerUserEntity.getId());
            studentVerifyMapper.delete(new LambdaQueryWrapper<StudentVerify>().eq(StudentVerify::getUserId, sellerUserEntity.getId()));
        }
        stringRedisTemplate.delete(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS);
    }

    /**
     * 测试用例 1: 【SEC-03】伪造脚本或纯文本伪装图片，文件头校验拦截 (400)
     */
    @Test
    @Order(1)
    void test01_fake_image_magic_bytes_rejected_400() {
        // 伪造的 PHP 脚本伪装为 .jpg
        byte[] fakePhp = "<?php echo 'malicious script'; phpinfo(); ?>".getBytes();
        MockMultipartFile fakeFile = new MockMultipartFile(
                "file",
                "shell.jpg",
                "image/jpeg",
                fakePhp
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> fileService.uploadImage(fakeFile));
        assertEquals(400, ex.getCode());
        assertEquals("文件内容不是有效的图片格式", ex.getMessage());

        // 伪装为 .png 的空文件/纯文本
        MockMultipartFile fakePng = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "NOT_AN_IMAGE_FILE_AT_ALL".getBytes()
        );
        ex = assertThrows(BusinessException.class, () -> fileService.uploadImage(fakePng));
        assertEquals(400, ex.getCode());
        assertEquals("文件内容不是有效的图片格式", ex.getMessage());
    }

    /**
     * 测试用例 2: 【SEC-03】合法的 PNG Magic Bytes 二进制流成功上传
     */
    @Test
    @Order(2)
    void test02_valid_png_magic_bytes_success() {
        // PNG 文件头: 89 50 4E 47 0D 0A 1A 0A + 填充数据
        byte[] validPngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
        MockMultipartFile pngFile = new MockMultipartFile(
                "file",
                "valid_image.png",
                "image/png",
                validPngHeader
        );

        String url = fileService.uploadImage(pngFile);
        assertNotNull(url);
        assertTrue(url.contains("goods/"));
        assertTrue(url.endsWith(".png"));
    }

    /**
     * 测试用例 3: 【SEC-03】合法的 JPEG Magic Bytes 二进制流成功上传
     */
    @Test
    @Order(3)
    void test03_valid_jpeg_magic_bytes_success() {
        // JPEG 文件头: FF D8 FF E0 ...
        byte[] validJpegHeader = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};
        MockMultipartFile jpegFile = new MockMultipartFile(
                "file",
                "valid_photo.jpeg",
                "image/jpeg",
                validJpegHeader
        );

        String url = fileService.uploadImage(jpegFile);
        assertNotNull(url);
        assertTrue(url.contains("goods/"));
        assertTrue(url.endsWith(".jpeg"));
    }

    /**
     * 测试用例 4: 【SEC-03】合法的 WebP Magic Bytes 二进制流成功上传
     */
    @Test
    @Order(4)
    void test04_valid_webp_magic_bytes_success() {
        // WebP 文件头: RIFF .... WEBP
        byte[] validWebpHeader = new byte[]{
                0x52, 0x49, 0x46, 0x46, // RIFF
                0x24, 0x00, 0x00, 0x00, // File Size
                0x57, 0x45, 0x42, 0x50  // WEBP
        };
        MockMultipartFile webpFile = new MockMultipartFile(
                "file",
                "banner.webp",
                "image/webp",
                validWebpHeader
        );

        String url = fileService.uploadImage(webpFile);
        assertNotNull(url);
        assertTrue(url.contains("goods/"));
        assertTrue(url.endsWith(".webp"));
    }

    /**
     * 测试用例 5: 【BIZ-02】商品详情浏览时，自动登记进入 Redis Set goods:views:dirty_ids
     */
    @Test
    @Order(5)
    void test05_goods_view_increments_and_registers_dirty_set() {
        // 清理当前商品的 Set 记录
        stringRedisTemplate.opsForSet().remove(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, testGoodsId.toString());

        // 触发浏览商品
        goodsService.getGoodsDetail(testGoodsId);

        // 验证当前 testGoodsId 已成功记录进 dirty_ids Set
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(
                RedisKeyConstants.GOODS_VIEW_DIRTY_IDS,
                testGoodsId.toString()
        );
        assertTrue(Boolean.TRUE.equals(isMember), "浏览商品时必须自动加入 goods:views:dirty_ids Set");
    }

    /**
     * 测试用例 6: 【BIZ-02】syncViewCounts() 批量消费 Set 脏 ID 落盘至 DB，无 keys(*) 阻塞扫描
     */
    @Test
    @Order(6)
    void test06_sync_view_counts_via_dirty_set_without_keys_scan() {
        Goods goodsBefore = goodsMapper.selectById(testGoodsId);
        int beforeDbViews = goodsBefore.getViewCount();

        // 模拟 Redis 累积了 25 次增量并登记进 Set
        String viewKey = RedisKeyConstants.goodsViewKey(testGoodsId);
        stringRedisTemplate.opsForValue().set(viewKey, "25");
        stringRedisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, testGoodsId.toString());

        // 执行同步任务
        goodsService.syncViewCounts();

        // 验证 DB 浏览量已累加 25
        Goods goodsAfter = goodsMapper.selectById(testGoodsId);
        assertEquals(beforeDbViews + 25, goodsAfter.getViewCount(), "DB 浏览量应准确累加 Redis 增量");

        // 验证 Redis 增量已原子扣减归零
        String remainingDelta = stringRedisTemplate.opsForValue().get(viewKey);
        assertNotNull(remainingDelta);
        assertEquals(0, Integer.parseInt(remainingDelta), "Redis 浏览量增量应被扣减重置为 0");

        // 验证 Set 中该商品 ID 已被 pop 消费清空
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(
                RedisKeyConstants.GOODS_VIEW_DIRTY_IDS,
                testGoodsId.toString()
        );
        assertFalse(Boolean.TRUE.equals(isMember), "同步完成后 dirty_ids Set 中的对应 ID 应已被弹出消费");
    }

    /**
     * 测试用例 7: 【ARCH-03 & @CurrentUser】Controller 统一路由与 @CurrentUser 参数自动注入
     */
    @Test
    @Order(7)
    void test07_current_user_resolver_in_report_controller() throws Exception {
        // 通过已规范化的 /reports 路由提交举报工单
        CreateReportRequest request = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(testGoodsId)
                .reasonType("FRAUD")
                .description("测试 @CurrentUser 参数解析器与统一路由")
                .build();

        mockMvc.perform(post("/reports")
                        .header("Authorization", "Bearer " + testToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetType").value("GOODS"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 同时校验落盘的 DB 记录确实是 testUserEntity.getId() 注入的
        Report savedReport = reportMapper.selectOne(
                new LambdaQueryWrapper<Report>()
                        .eq(Report::getTargetId, testGoodsId)
                        .eq(Report::getTargetType, "GOODS")
        );
        assertNotNull(savedReport, "举报记录必须成功落库");
        assertEquals(testUserEntity.getId(), savedReport.getReporterId(), "@CurrentUser 应准确将登录用户实体与 ID 注入 Service");
    }

    /**
     * 测试用例 8: 【@CurrentUser】未登录时被参数解析器或安全拦截器统一处理为 401
     */
    @Test
    @Order(8)
    void test08_current_user_resolver_unauthorized_returns_401() throws Exception {
        CreateReportRequest request = CreateReportRequest.builder()
                .targetType("GOODS")
                .targetId(testGoodsId)
                .reasonType("FRAUD")
                .description("未登录提交举报测试")
                .build();

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
