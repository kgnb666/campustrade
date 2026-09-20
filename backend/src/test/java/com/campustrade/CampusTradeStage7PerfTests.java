package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campustrade.common.util.SearchKeywordUtils;
import com.campustrade.dto.GoodsQueryDTO;
import com.campustrade.dto.report.ReportQueryRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.GoodsService;
import com.campustrade.support.SqlStatementCounter;
import com.campustrade.vo.GoodsListVO;
import com.campustrade.vo.report.AdminReportDetailVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 阶段 7：性能与体验优化回归测试
 *
 * <p>覆盖四类"无法用业务返回值断言"的性能事实，把它们变成可重复执行的护栏：</p>
 * <ol>
 *   <li><b>V11 迁移与索引治理</b>：两条复合索引存在、5 个重复索引已删除、view_count 已放大为 BIGINT；</li>
 *   <li><b>关键词通配符转义</b>：输入 {@code _} / {@code %} 只匹配字面字符，不再命中全表；</li>
 *   <li><b>管理员工单列表 N+1</b>：用 MyBatis 拦截器实测"单请求 SQL 条数"，并断言其不随 pageSize 线性增长；</li>
 *   <li><b>接口契约不变</b>：商品列表分页字段（total/pages/records）与"我的商品"列表结构保持不变。</li>
 * </ol>
 *
 * <p>这些用例只做只读断言 + 自建夹具数据（不依赖开发库，测试库由 Testcontainers 提供）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage7PerfTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AdminGovernanceService adminGovernanceService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private ReportMapper reportMapper;

    /** SQL 语句计数器：每个用例前清零，用例内只统计本用例真正发出的语句。 */
    private static final SqlStatementCounter sqlCounter = new SqlStatementCounter();

    private static final Long ADMIN_ID = 88770099L;
    private static final Long REPORTER_ID = 88770001L;
    private static final Long SELLER_ID = 88770002L;

    /** 工单分页夹具规模：与 pageSize=100 一起使用，用于暴露"每行 1~3 条查询"的放大效应。 */
    private static final int REPORT_FIXTURE_PENDING = 60;

    /** 已处理工单夹具：带 handled_by，用于覆盖"每行 3 条查询（举报人 + 处理人 + 目标快照）"的最坏形态。 */
    private static final int REPORT_FIXTURE_HANDLED_PER_BUCKET = 20;

    /** 夹具标题前缀：刻意不含通配符，避免影响 test03 对 _ / % 的精确断言。 */
    private static final String WILDCARD_FIXTURE_PREFIX = "阶段7通配符夹具";

    @BeforeEach
    void setUp() {
        sqlCounter.install(sqlSessionFactory.getConfiguration());
        initUser(ADMIN_ID, "stage7perf_admin", "性能测试管理员", "ADMIN");
        initUser(REPORTER_ID, "stage7perf_reporter", "性能测试举报人", "USER");
        initUser(SELLER_ID, "stage7perf_seller", "性能测试卖家", "USER");
    }

    @AfterEach
    void tearDown() {
        sqlCounter.reset();
    }

    private void initUser(Long id, String username, String nickname, String role) {
        User existed = userMapper.selectById(id);
        if (existed == null) {
            userMapper.insert(User.builder()
                    .id(id)
                    .username(username)
                    .password("$2a$10$abcdefghijklmnopqrstuvwxyz1234567890")
                    .nickname(nickname)
                    .role(role)
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build());
        }
        UserCredit credit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, id));
        if (credit == null) {
            userCreditMapper.insert(UserCredit.builder()
                    .userId(id)
                    .creditScore(100)
                    .creditLevel("GOOD")
                    .tradeCount(0)
                    .goodReviewCount(0)
                    .badReviewCount(0)
                    .completedCount(0L)
                    .cancelCount(0L)
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build());
        }
    }

    // =========================================================================
    // 一、V11 迁移：索引治理落地
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. V11 迁移已生效：两条商品列表复合索引存在，view_count 已放大为 BIGINT")
    void test01_v11_indexes_applied() {
        assertTrue(indexExists("goods", "idx_goods_status_created"),
                "V11 必须创建 (status, created_time DESC) 复合索引");
        assertTrue(indexExists("goods", "idx_goods_status_category_created"),
                "V11 必须创建 (status, category_id, created_time DESC) 复合索引");

        // 索引列顺序必须与"过滤列在前、排序列在后"的设计一致，否则无法同时承担过滤与排序
        assertEquals("status,created_time",
                indexColumns("idx_goods_status_created"));
        assertEquals("status,category_id,created_time",
                indexColumns("idx_goods_status_category_created"));

        String viewCountType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema='campus_trade' AND table_name='goods' AND column_name='view_count'",
                String.class);
        assertEquals("bigint", viewCountType, "V11 必须把 goods.view_count 放大为 BIGINT");
    }

    @Test
    @Order(2)
    @DisplayName("2. V11 迁移已生效：5 个重复覆盖的冗余索引已删除，且各自仍有等价索引承接访问")
    void test02_redundant_indexes_dropped() {
        // 已删除的重复索引
        assertFalse(indexExists("user", "idx_user_username"), "idx_user_username 与唯一索引 user_username_key 完全重复，应删除");
        assertFalse(indexExists("user", "idx_user_email"), "idx_user_email 与唯一索引 user_email_key 完全重复，应删除");
        assertFalse(indexExists("user_credit", "idx_user_credit_user"), "idx_user_credit_user 与唯一索引 user_credit_user_id_key 完全重复，应删除");
        assertFalse(indexExists("review_like", "idx_review_like_review"), "idx_review_like_review 是 uk_review_like_review_user 的前缀，应删除");
        assertFalse(indexExists("favorite", "idx_favorite_user_id"), "idx_favorite_user_id 是 idx_favorite_user_time 的前缀，应删除");

        // 承接访问的索引必须都还在（删除不得破坏唯一性约束与既有访问路径）
        assertTrue(indexExists("user", "user_username_key"), "username 唯一性约束索引不可删除");
        assertTrue(indexExists("user", "user_email_key"), "email 唯一性约束索引不可删除");
        assertTrue(indexExists("user_credit", "user_credit_user_id_key"), "一人一份信用档案的唯一性约束索引不可删除");
        assertTrue(indexExists("review_like", "uk_review_like_review_user"), "一人一评仅点赞一次的唯一索引不可删除");
        assertTrue(indexExists("favorite", "idx_favorite_user_time"), "按时间看收藏的复合索引不可删除");
        assertTrue(indexExists("favorite", "uk_favorite_user_goods"), "收藏防重唯一索引不可删除");
    }

    private boolean indexExists(String table, String indexName) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='campus_trade' AND tablename=? AND indexname=?",
                Integer.class, table, indexName);
        return cnt != null && cnt > 0;
    }

    /** 返回索引列（去掉 DESC 等排序修饰，逗号分隔），用于断言"列顺序即设计意图"。 */
    private String indexColumns(String indexName) {
        String def = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='campus_trade' AND indexname=?",
                String.class, indexName);
        assertNotNull(def, "索引不存在: " + indexName);
        String columns = def.substring(def.indexOf('(') + 1, def.lastIndexOf(')'));
        return columns.replace(" DESC", "").replace(" ", "");
    }

    // =========================================================================
    // 二、关键词通配符转义（LIKE ... ESCAPE）
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("3. 关键词中的 LIKE 通配符被转义：输入 _ / % 不再命中全部商品")
    void test03_like_wildcards_escaped() {
        String titleWithUnderscore = WILDCARD_FIXTURE_PREFIX + "下划线_商品";
        String titleWithPercent = WILDCARD_FIXTURE_PREFIX + "百分号%商品";
        Long idUnderscore = createGoods(titleWithUnderscore);
        Long idPercent = createGoods(titleWithPercent);
        // 额外的"干净"在售商品（不含任何通配符）：让"命中总数 < 在售总数"这个断言具备区分度
        List<Long> fillerIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fillerIds.add(createGoods(WILDCARD_FIXTURE_PREFIX + "普通商品 #" + i));
        }

        try {
            // 1) 转义工具本身：三类元字符都必须被反斜杠转义
            assertEquals("a\\_b", SearchKeywordUtils.escapeLikePattern("a_b"));
            assertEquals("a\\%b", SearchKeywordUtils.escapeLikePattern("a%b"));
            assertEquals("a\\\\b", SearchKeywordUtils.escapeLikePattern("a\\b"));
            assertNull(SearchKeywordUtils.escapeLikePattern(null), "空关键词不应产生匹配模式");
            assertNull(SearchKeywordUtils.escapeLikePattern("   "), "纯空白关键词不应产生匹配模式");

            int totalOnSale = countOnSaleGoods();
            assertTrue(totalOnSale >= 5, "夹具必须落在在售商品集合内，实际在售=" + totalOnSale);

            // 2) 端到端："_" 只匹配"字面下划线"。
            //    修复前它会变成 LIKE '%_%'（匹配任意非空串），total 会等于全部在售商品数。
            IPage<GoodsListVO> underscorePage = searchByKeyword("_");
            List<GoodsListVO> underscoreHits = underscorePage.getRecords();
            assertTrue(underscoreHits.stream().anyMatch(v -> idUnderscore.equals(v.getId())),
                    "转义后 '_' 必须能命中标题里真的含下划线的商品");
            assertFalse(underscoreHits.stream().anyMatch(v -> idPercent.equals(v.getId())),
                    "转义后 '_' 不得命中不含下划线的商品");
            assertTrue(underscorePage.getTotal() < totalOnSale,
                    "转义后 '_' 的命中总数必须小于在售商品总数（修复前等于全表）: total="
                            + underscorePage.getTotal() + ", onSale=" + totalOnSale);
            System.out.println("[阶段7] keyword='_' 命中 total=" + underscorePage.getTotal()
                    + "（在售商品总数=" + totalOnSale + "）");

            // 3) 端到端："%" 同理，修复前是 LIKE '%%%'（匹配任意串）
            IPage<GoodsListVO> percentPage = searchByKeyword("%");
            List<GoodsListVO> percentHits = percentPage.getRecords();
            assertTrue(percentHits.stream().anyMatch(v -> idPercent.equals(v.getId())),
                    "转义后 '%' 必须能命中标题里真的含百分号的商品");
            assertFalse(percentHits.stream().anyMatch(v -> idUnderscore.equals(v.getId())),
                    "转义后 '%' 不得命中不含百分号的商品");
            assertTrue(percentPage.getTotal() < totalOnSale,
                    "转义后 '%' 的命中总数必须小于在售商品总数（修复前等于全表）: total="
                            + percentPage.getTotal() + ", onSale=" + totalOnSale);
            System.out.println("[阶段7] keyword='%' 命中 total=" + percentPage.getTotal()
                    + "（在售商品总数=" + totalOnSale + "）");

            // 4) 不含通配符的普通关键词仍正常工作（不得因为加转义而漏配）
            List<GoodsListVO> plainHits = searchByKeyword("百分号").getRecords();
            assertTrue(plainHits.stream().anyMatch(v -> idPercent.equals(v.getId())),
                    "普通中文关键词必须仍能命中");
        } finally {
            goodsMapper.deleteById(idUnderscore);
            goodsMapper.deleteById(idPercent);
            for (Long fillerId : fillerIds) {
                goodsMapper.deleteById(fillerId);
            }
        }
    }

    private int countOnSaleGoods() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM campus_trade.goods WHERE status='ON_SALE'", Integer.class);
        return count == null ? 0 : count;
    }

    private IPage<GoodsListVO> searchByKeyword(String keyword) {
        GoodsQueryDTO query = GoodsQueryDTO.builder().page(1).size(50).keyword(keyword).build();
        return goodsService.pageGoods(query);
    }

    private Long createGoods(String title) {
        Goods goods = Goods.builder()
                .sellerId(SELLER_ID)
                .schoolId(1L)
                .categoryId(1L)
                .title(title)
                .description("阶段 7 性能用例夹具")
                .price(new BigDecimal("9.90"))
                .conditionLevel("95新")
                .status("ON_SALE")
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);
        return goods.getId();
    }

    // =========================================================================
    // 三、管理员工单列表 N+1 改造：单请求 SQL 条数
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("4. 管理员工单列表不再 N+1：单请求 SQL 条数与 pageSize 解耦（改造前为每行 2~3 条）")
    void test04_admin_report_page_sql_statement_count() {
        seedReports();

        // 不带筛选条件 => 单页同时包含 PENDING 与已处理工单，覆盖"每行最多 3 条关联查询"的最坏形态
        ReportQueryRequest query = ReportQueryRequest.builder().page(1).size(100).build();

        sqlCounter.reset();
        IPage<AdminReportDetailVO> page = adminGovernanceService.pageReports(query);
        int statements = sqlCounter.count();
        int rows = page.getRecords().size();

        assertTrue(rows >= 100, "夹具工单必须被分页查询返回，实际返回=" + rows);
        assertTrue(page.getRecords().stream().anyMatch(v -> v.getHandlerUsername() != null),
                "夹具必须包含已处理工单（handled_by 非空），否则覆盖不到每行 3 次查询的最坏形态");

        System.out.println("=========== 阶段7 N+1 实测：管理员工单列表 ===========");
        System.out.println("[阶段7] 工单行数=" + rows + "，单请求实际 SQL 条数=" + statements);
        System.out.println("[阶段7] 改造前口径：每行 2~3 条关联查询 + count + 分页 = 2N+2 ~ 3N+2 条；"
                + "本夹具 N=" + rows + " 时约为 " + (2 * rows + 2) + " ~ " + (3 * rows + 2) + " 条");
        System.out.println("[阶段7] SQL 汇总=" + sqlCounter.summary());
        for (int i = 0; i < sqlCounter.executedSqlSnippets().size(); i++) {
            System.out.println("[阶段7]   SQL#" + (i + 1) + " " + sqlCounter.executedSqlSnippets().get(i));
        }

        // 批量聚合后：count + 分页 + 用户批量（举报人 ∪ 处理人）+ 每种 targetType 一条批量快照，
        // 与行数完全解耦（夹具覆盖 GOODS/REVIEW/USER 三种 targetType，故上界取 8）。
        assertTrue(statements <= 8,
                "工单分页单请求 SQL 条数必须与 pageSize 解耦（应 ≤ 8），实际=" + statements
                        + "，汇总=" + sqlCounter.summary());

        // 详情接口（单行）也必须是常数条查询（不随数据量放大）
        sqlCounter.reset();
        adminGovernanceService.getReportDetail(page.getRecords().get(0).getId());
        int detailStatements = sqlCounter.count();
        System.out.println("[阶段7] 单条工单详情 SQL 条数=" + detailStatements);
        assertTrue(detailStatements <= 4, "单条工单详情不应放大，实际=" + detailStatements
                + "，汇总=" + sqlCounter.summary());
    }

    /**
     * 造工单夹具：GOODS 目标（PENDING）+ REVIEW/USER 目标（已处理，带处理人），共 100 条，
     * 使 page(1,100) 一页同时覆盖三种 targetType 与"有无处理人"两种分支。
     */
    private void seedReports() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM campus_trade.report WHERE reporter_id=? AND description LIKE '阶段7 N+1 夹具%'",
                Integer.class, REPORTER_ID);
        if (exists != null && exists >= 100) {
            return;
        }

        // 1) GOODS 目标 + PENDING：目标商品真实存在，快照能查到内容
        for (int i = 0; i < REPORT_FIXTURE_PENDING; i++) {
            Long targetId = createGoods(WILDCARD_FIXTURE_PREFIX + "工单目标 #" + i);
            reportMapper.insert(Report.builder()
                    .reporterId(REPORTER_ID)
                    .targetType("GOODS")
                    .targetId(targetId)
                    .reasonType("FRAUD")
                    .description("阶段7 N+1 夹具 GOODS #" + i)
                    .status("PENDING")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build());
        }

        // 2) REVIEW / USER 目标 + 已处理：覆盖"处理人查询"分支（report.target_id 无外键，可指向任意业务 ID）
        for (String targetType : List.of("REVIEW", "USER")) {
            for (int i = 0; i < REPORT_FIXTURE_HANDLED_PER_BUCKET; i++) {
                reportMapper.insert(Report.builder()
                        .reporterId(REPORTER_ID)
                        .targetType(targetType)
                        .targetId(900000000L + i)
                        .reasonType("HARASSMENT")
                        .description("阶段7 N+1 夹具 " + targetType + " #" + i)
                        .status("HANDLED_VALID")
                        .handledBy(ADMIN_ID)
                        .handledTime(LocalDateTime.now())
                        .handleResult("夹具已处置")
                        .createdTime(LocalDateTime.now())
                        .updatedTime(LocalDateTime.now())
                        .build());
            }
        }
    }

    // =========================================================================
    // 四、接口契约不变（分页字段与返回结构）
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 商品列表分页契约不变：total/pages/records 字段与 pageSize 约束保持一致")
    void test05_goods_list_pagination_contract_unchanged() throws Exception {
        Long goodsId = createGoods(WILDCARD_FIXTURE_PREFIX + "契约夹具");
        try {
            MvcResult result = mockMvc.perform(get("/goods/list").param("page", "1").param("size", "10"))
                    .andReturn();
            assertEquals(200, result.getResponse().getStatus());
            JsonNode body = objectMapper.readTree(
                    new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
            assertEquals(200, body.get("code").asInt());
            JsonNode data = body.get("data");

            // 结构（字段名）不得变化
            assertTrue(data.has("records"), "分页对象必须保留 records");
            assertTrue(data.has("total"), "分页对象必须保留 total（searchCount 未被关闭）");
            assertTrue(data.has("pages"), "分页对象必须保留 pages");
            assertTrue(data.has("current"), "分页对象必须保留 current");
            assertTrue(data.has("size"), "分页对象必须保留 size");
            assertTrue(data.get("total").asLong() > 0, "total 必须真实统计（前端依赖 pages 判定 hasMore）");
            assertTrue(data.get("records").size() <= 10, "必须遵守 size 上限");

            JsonNode first = data.get("records").get(0);
            for (String field : List.of("id", "sellerId", "schoolId", "schoolName", "categoryId",
                    "categoryName", "title", "coverImage", "price", "conditionLevel", "status",
                    "viewCount", "createdTime")) {
                assertTrue(first.has(field), "商品列表字段不得缺失: " + field);
            }
        } finally {
            goodsMapper.deleteById(goodsId);
        }
    }

    @Test
    @Order(6)
    @DisplayName("6. 我的商品：默认调用仍是 List 结构（契约不变），可选 page/size 走分页且不改变字段")
    void test06_list_my_goods_paging_optional() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(SELLER_ID, "stage7perf_seller", "USER");
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long id = createGoods(WILDCARD_FIXTURE_PREFIX + "我的商品夹具 #" + i);
            created.add(id);
        }
        try {
            // 不带分页参数的调用：结构与字段保持原样（List）
            MvcResult all = mockMvc.perform(get("/goods/my").header("Authorization", "Bearer " + token))
                    .andReturn();
            assertEquals(200, all.getResponse().getStatus());
            JsonNode allBody = objectMapper.readTree(
                    new String(all.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
            JsonNode allData = allBody.get("data");
            assertTrue(allData.isArray(), "默认调用必须仍然返回数组（接口结构不变）");
            assertTrue(allData.size() >= 5, "默认调用必须返回全部商品");
            assertTrue(allData.get(0).has("coverImage"), "字段不得因分页改造而变化");

            // 带分页参数：仍是数组，但只返回该页
            MvcResult paged = mockMvc.perform(get("/goods/my")
                            .header("Authorization", "Bearer " + token)
                            .param("page", "1").param("size", "2"))
                    .andReturn();
            assertEquals(200, paged.getResponse().getStatus());
            JsonNode pagedData = objectMapper.readTree(
                    new String(paged.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).get("data");
            assertTrue(pagedData.isArray(), "分页调用同样返回数组（结构不变）");
            assertEquals(2, pagedData.size(), "分页调用必须只返回 size 条");
        } finally {
            for (Long id : created) {
                goodsMapper.deleteById(id);
            }
        }
    }

}
