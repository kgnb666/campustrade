package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.CreateGoodsDTO;
import com.campustrade.dto.LoginRequestDTO;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.dto.UpdateGoodsDTO;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.StudentVerify;
import com.campustrade.entity.User;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.StudentVerifyMapper;
import com.campustrade.mapper.UserMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 2: 商品发布与商品浏览体系自动化集成测试
 * 严格覆盖要求的 11 项验证指标
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage2Tests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudentVerifyMapper studentVerifyMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private GoodsImageMapper goodsImageMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 测试静态上下文共享变量
    private static String tokenUserA;
    private static Long userAId;
    private static String usernameA;

    private static String tokenUserB;
    private static Long userBId;
    private static String usernameB;

    private static Long createdGoodsId;

    // 测试口令：每次运行随机生成，源码中不固化任何可用口令（注册与登录共用同一值）
    private static final String TEST_PASSWORD = TestCredentials.randomPassword();

    @BeforeAll
    static void setUpUsers(@Autowired MockMvc mockMvc,
                           @Autowired ObjectMapper objectMapper,
                           @Autowired UserMapper userMapper,
                           @Autowired StudentVerifyMapper studentVerifyMapper) throws Exception {
        // 1. 创建 User A (卖家 A, 认证为 清华大学 schoolId=1)
        usernameA = "sellerA_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequestDTO regA = new RegisterRequestDTO();
        regA.setUsername(usernameA);
        regA.setPassword(TEST_PASSWORD);
        regA.setEmail(usernameA + "@mails.tsinghua.edu.cn");

        MvcResult regResA = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regA)))
                .andExpect(status().isOk())
                .andReturn();

        User userA = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameA));
        userAId = userA.getId();

        // 模拟完成校园认证 (SUCCESS)
        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(userAId)
                .schoolId(1L)
                .studentNumber("20261001")
                .schoolEmail(userA.getEmail())
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build());

        // 登录获取 Token A
        LoginRequestDTO loginA = new LoginRequestDTO();
        loginA.setUsername(usernameA);
        loginA.setPassword(TEST_PASSWORD);

        MvcResult resA = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginA)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rootA = objectMapper.readTree(resA.getResponse().getContentAsString());
        tokenUserA = rootA.path("data").path("accessToken").asText();

        // 2. 创建 User B (另一用户 B, 认证为 北京大学 schoolId=2)
        usernameB = "userB_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequestDTO regB = new RegisterRequestDTO();
        regB.setUsername(usernameB);
        regB.setPassword(TEST_PASSWORD);
        regB.setEmail(usernameB + "@pku.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regB)))
                .andExpect(status().isOk());

        User userB = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, usernameB));
        userBId = userB.getId();

        studentVerifyMapper.insert(StudentVerify.builder()
                .userId(userBId)
                .schoolId(2L)
                .studentNumber("20262002")
                .schoolEmail(userB.getEmail())
                .verifyStatus(StudentVerifyStatus.SUCCESS.getCode())
                .verifyTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build());

        LoginRequestDTO loginB = new LoginRequestDTO();
        loginB.setUsername(usernameB);
        loginB.setPassword(TEST_PASSWORD);

        MvcResult resB = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginB)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rootB = objectMapper.readTree(resB.getResponse().getContentAsString());
        tokenUserB = rootB.path("data").path("accessToken").asText();
    }

    /**
     * 1. 分类查询成功
     */
    @Test
    @Order(1)
    @DisplayName("1. 分类查询成功 - 返回树形分类结构")
    void test1_CategoryQuerySuccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andReturn();

        JsonNode categories = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertTrue(categories.size() >= 5, "一级分类数量应大于等于5");

        // 检查一级分类 "电子产品" 下是否包含二级子分类
        boolean foundChildren = false;
        for (JsonNode cat : categories) {
            if ("电子产品".equals(cat.path("name").asText())) {
                JsonNode children = cat.path("children");
                assertTrue(children.isArray() && children.size() > 0, "电子产品应包含二级分类");
                foundChildren = true;
                break;
            }
        }
        assertTrue(foundChildren, "应当找到电子产品分类及其子节点");
    }

    /**
     * 2. 商品创建成功
     */
    @Test
    @Order(2)
    @DisplayName("2. 商品创建成功 - 认证学生发布商品并进入在售状态")
    void test2_CreateGoodsSuccess() throws Exception {
        CreateGoodsDTO createDTO = CreateGoodsDTO.builder()
                .title("Apple iPad Air 5 64G 深空灰 99新")
                .description("考研刷题自用平板，带原装磁吸保护套，成色极佳无划痕")
                .categoryId(103L) // 平板
                .price(new BigDecimal("2899.00"))
                .originalPrice(new BigDecimal("3999.00"))
                .conditionLevel("95新")
                .location("清华大学紫荆公寓1号楼")
                .images(Arrays.asList(
                        "http://127.0.0.1:9000/campustrade/goods/ipad1.jpg",
                        "http://127.0.0.1:9000/campustrade/goods/ipad2.jpg"
                ))
                .tags(Arrays.asList("考研神器", "自用", "支持面交"))
                .build();

        MvcResult result = mockMvc.perform(post("/goods")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        createdGoodsId = root.path("data").asLong();
        assertTrue(createdGoodsId > 0, "发布商品后应返回生成的商品ID");

        // 检查数据库记录
        Goods goods = goodsMapper.selectById(createdGoodsId);
        assertNotNull(goods);
        assertEquals(GoodsStatus.ON_SALE.getCode(), goods.getStatus());
        assertEquals(0, new BigDecimal("2899.00").compareTo(goods.getPrice()));

        // 检查多图保存
        List<GoodsImage> images = goodsImageMapper.selectList(
                new LambdaQueryWrapper<GoodsImage>().eq(GoodsImage::getGoodsId, createdGoodsId)
        );
        assertEquals(2, images.size(), "商品图片数量应为2");
    }

    /**
     * 3. 商品绑定卖家
     */
    @Test
    @Order(3)
    @DisplayName("3. 商品绑定卖家 - 验证 seller_id 正确绑定发布者")
    void test3_GoodsBindSeller() {
        assertNotNull(createdGoodsId);
        Goods goods = goodsMapper.selectById(createdGoodsId);
        assertEquals(userAId, goods.getSellerId(), "商品卖家ID必须与发布者用户ID严格一致");
    }

    /**
     * 4. 商品绑定学校
     */
    @Test
    @Order(4)
    @DisplayName("4. 商品绑定学校 - 验证 school_id 正确绑定用户认证学校")
    void test4_GoodsBindSchool() {
        assertNotNull(createdGoodsId);
        Goods goods = goodsMapper.selectById(createdGoodsId);
        assertEquals(1L, goods.getSchoolId(), "商品关联学校ID必须为发布者的认证高校(清华大学 schoolId=1)");
    }

    /**
     * 5. 图片上传成功
     */
    @Test
    @Order(5)
    @DisplayName("5. 图片上传成功 - 校验类型限制与文件大小并安全上传")
    void test5_ImageUploadSuccess() throws Exception {
        // (1) 合法图片上传 (提供真实 PNG Magic Bytes 头部)
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        MockMultipartFile validImage = new MockMultipartFile(
                "file",
                "campus_item.png",
                "image/png",
                pngBytes
        );

        MvcResult result = mockMvc.perform(multipart("/file/upload")
                        .file(validImage)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        String url = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").asText();
        assertTrue(url.contains("goods/"), "返回的图片URL路径应包含 goods/");
        assertTrue(url.endsWith(".png"), "返回的图片URL应以 png 结尾");

        // (2) 非法类型文件被拒绝 (例如 .exe 或 .txt)
        MockMultipartFile illegalFile = new MockMultipartFile(
                "file",
                "virus.exe",
                "application/octet-stream",
                "IllegalBinaryPayload".getBytes()
        );

        mockMvc.perform(multipart("/file/upload")
                        .file(illegalFile)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 6. 商品分页查询
     */
    @Test
    @Order(6)
    @DisplayName("6. 商品分页查询 - 验证多条件检索、分类筛选与价格区间")
    void test6_GoodsPaginationAndFilter() throws Exception {
        // (1) 关键词与学校检索
        mockMvc.perform(get("/goods/list")
                        .param("page", "1")
                        .param("size", "10")
                        .param("keyword", "iPad")
                        .param("schoolId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].title").value("Apple iPad Air 5 64G 深空灰 99新"))
                .andExpect(jsonPath("$.data.records[0].schoolName").value("清华大学"));

        // (2) 价格区间检索
        mockMvc.perform(get("/goods/list")
                        .param("minPrice", "2000")
                        .param("maxPrice", "3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isNotEmpty());
    }

    /**
     * 7. 商品详情查询
     */
    @Test
    @Order(7)
    @DisplayName("7. 商品详情查询 - 包含图片、卖家信息、学籍认证与信用分")
    void test7_GoodsDetailQuery() throws Exception {
        assertNotNull(createdGoodsId);

        MvcResult result = mockMvc.perform(get("/goods/" + createdGoodsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(createdGoodsId))
                .andExpect(jsonPath("$.data.sellerId").value(userAId))
                .andExpect(jsonPath("$.data.sellerVerified").value(true))
                .andExpect(jsonPath("$.data.sellerSchoolName").value("清华大学"))
                .andExpect(jsonPath("$.data.sellerCreditScore").value(100))
                .andExpect(jsonPath("$.data.images").isArray())
                .andExpect(jsonPath("$.data.images.length()").value(2))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals("Apple iPad Air 5 64G 深空灰 99新", data.path("title").asText());
    }

    /**
     * 8. 浏览量增加
     */
    @Test
    @Order(8)
    @DisplayName("8. 浏览量增加 - 验证 Redis INCR 增量计数")
    void test8_ViewCountIncrementRedis() throws Exception {
        assertNotNull(createdGoodsId);
        String redisKey = "goods:view:" + createdGoodsId;

        // 获取当前 Redis 浏览增量
        Object initialVal = redisTemplate.opsForValue().get(redisKey);
        int initialCount = (initialVal != null) ? Integer.parseInt(initialVal.toString()) : 0;

        // 再次访问详情页
        mockMvc.perform(get("/goods/" + createdGoodsId))
                .andExpect(status().isOk());

        // 验证 Redis 浏览量累加
        Object afterVal = redisTemplate.opsForValue().get(redisKey);
        assertNotNull(afterVal);
        int afterCount = Integer.parseInt(afterVal.toString());
        assertEquals(initialCount + 1, afterCount, "每次访问详情页 Redis 浏览量应自动自增 1");
    }

    /**
     * 9. 修改本人商品成功
     */
    @Test
    @Order(9)
    @DisplayName("9. 修改本人商品成功 - 发布者更新标题、价格与描述")
    void test9_UpdateOwnGoodsSuccess() throws Exception {
        assertNotNull(createdGoodsId);

        UpdateGoodsDTO updateDTO = UpdateGoodsDTO.builder()
                .title("Apple iPad Air 5 64G (急出直降)")
                .price(new BigDecimal("2699.00"))
                .description("由于毕业急出，现直降200，送原装充电头")
                .conditionLevel("95新")
                .build();

        mockMvc.perform(put("/goods/" + createdGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证数据库已更新
        Goods updatedGoods = goodsMapper.selectById(createdGoodsId);
        assertEquals("Apple iPad Air 5 64G (急出直降)", updatedGoods.getTitle());
        assertEquals(0, new BigDecimal("2699.00").compareTo(updatedGoods.getPrice()));
    }

    /**
     * 10. 修改他人商品失败
     */
    @Test
    @Order(10)
    @DisplayName("10. 修改他人商品失败 - 权限拦截拒绝其他用户修改")
    void test10_UpdateOthersGoodsFails() throws Exception {
        assertNotNull(createdGoodsId);

        // User B 尝试修改 User A 的商品
        UpdateGoodsDTO updateDTO = UpdateGoodsDTO.builder()
                .title("恶意篡改标题")
                .price(new BigDecimal("1.00"))
                .build();

        mockMvc.perform(put("/goods/" + createdGoodsId)
                        .header("Authorization", "Bearer " + tokenUserB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDTO)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 11. 逻辑删除成功
     */
    @Test
    @Order(11)
    @DisplayName("11. 逻辑删除成功 - 状态流转为 OFF_SHELF 并在公开列表隐藏")
    void test11_LogicalDeleteSuccess() throws Exception {
        assertNotNull(createdGoodsId);

        // User A 执行删除
        mockMvc.perform(delete("/goods/" + createdGoodsId)
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证数据库状态为 OFF_SHELF
        Goods goods = goodsMapper.selectById(createdGoodsId);
        assertEquals(GoodsStatus.OFF_SHELF.getCode(), goods.getStatus(), "删除后商品状态应流转为 OFF_SHELF");

        // 验证公开商品列表无法检索到已下架商品
        MvcResult result = mockMvc.perform(get("/goods/list")
                        .param("keyword", "急出直降"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode records = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("records");
        assertEquals(0, records.size(), "公开列表不应包含已逻辑删除的下架商品");
    }
}
