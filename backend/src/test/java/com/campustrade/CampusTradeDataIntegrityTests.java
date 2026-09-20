package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.dto.report.HandleReportRequest;
import com.campustrade.dto.review.CreateReviewRequest;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.Review;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.OrderStatus;
import com.campustrade.enums.ReviewStatus;
import com.campustrade.exception.BusinessException;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.service.AdminGovernanceService;
import com.campustrade.service.CreditService;
import com.campustrade.service.GoodsService;
import com.campustrade.service.OrderService;
import com.campustrade.service.ReviewService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 4：数据一致性缺陷修复回归测试套件
 *
 * <p>覆盖四个必测场景（每个都对应一处已修复的一致性缺陷）：</p>
 * <ol>
 *   <li>并发「下单锁货 + 浏览量同步」：商品 status 不回退为 ON_SALE、view_count 不减少；</li>
 *   <li>浏览量刷盘写库失败一次后重试：Redis 增量不丢失，且最终只落盘一次（不重复）；</li>
 *   <li>信用对账：{@code 100 + SUM(change_score) == credit_score}，且
 *       「屏蔽 → 恢复 → 再次屏蔽」三次治理动作各自生效（旧实现第三次被幂等键静默拦截）；</li>
 *   <li>并发处理同一举报工单：只有一次生效，admin_audit_log 只新增一条。</li>
 * </ol>
 *
 * <p>另含两条针对"并发初始化"与"唯一约束冲突业务捕获"的回归用例
 * （并发首次初始化信用档案不再 500；并发重复评价必须得到 409 而不是 500）。</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeDataIntegrityTests {

    private static final Long SELLER_ID = 9200001L;
    private static final Long BUYER_ID = 9200002L;
    private static final Long THIRD_PARTY_ID = 9200003L;
    private static final Long ADMIN_ID = 9200099L;
    private static final Long FRESH_CREDIT_USER_ID = 9200007L;

    private static final String ADMIN_USERNAME = "stage4_integrity_admin";

    @Autowired
    private OrderService orderService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private AdminGovernanceService adminGovernanceService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private ReportMapper reportMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 本测试类创建过的商品 ID，用于 @AfterEach 按外键依赖顺序清理。 */
    private final List<Long> createdGoodsIds = new ArrayList<>();

    /** 本测试类创建过的评价 ID（清理时先于 order/goods 删除）。 */
    private final List<Long> createdReviewIds = new ArrayList<>();

    // =========================================================================
    // 夹具与清理
    // =========================================================================

    @BeforeEach
    void setUp() {
        initUser(SELLER_ID, "stage4_integrity_seller", "一致性卖家", "USER");
        initUser(BUYER_ID, "stage4_integrity_buyer", "一致性买家", "USER");
        initUser(THIRD_PARTY_ID, "stage4_integrity_third", "一致性路人", "USER");
        initUser(ADMIN_ID, ADMIN_USERNAME, "一致性管理员", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanupCreatedData();
    }

    private void cleanupCreatedData() {
        // 严格按外键依赖倒序清理：report(无外键) -> review_like -> review -> trade_order -> favorite
        // -> goods_image -> goods
        if (!createdReviewIds.isEmpty()) {
            String reviewIds = createdReviewIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM campus_trade.report WHERE target_type = 'REVIEW' AND target_id IN ("
                    + reviewIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.review_like WHERE review_id IN (" + reviewIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.review WHERE id IN (" + reviewIds + ")");
        }
        if (!createdGoodsIds.isEmpty()) {
            String goodsIds = createdGoodsIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM campus_trade.report WHERE target_type = 'GOODS' AND target_id IN ("
                    + goodsIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.favorite WHERE goods_id IN (" + goodsIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE goods_id IN (" + goodsIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.goods_image WHERE goods_id IN (" + goodsIds + ")");
            jdbcTemplate.update("DELETE FROM campus_trade.goods WHERE id IN (" + goodsIds + ")");
            for (Long goodsId : createdGoodsIds) {
                stringRedisTemplate.delete(RedisKeyConstants.goodsViewKey(goodsId));
                stringRedisTemplate.opsForSet().remove(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString());
            }
        }
        createdGoodsIds.clear();
        createdReviewIds.clear();
    }

    private void initUser(Long id, String username, String nickname, String role) {
        User existing = userMapper.selectById(id);
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now();
            userMapper.insert(User.builder()
                    .id(id)
                    .username(username)
                    .password("$2a$10$abcdefghijklmnopqrstuvwxyz1234567890")
                    .nickname(nickname)
                    .role(role)
                    .status("ACTIVE")
                    .createdTime(now)
                    .updatedTime(now)
                    .build());
        }
    }

    private Long createGoods(String status, int viewCount) {
        LocalDateTime now = LocalDateTime.now();
        Goods goods = Goods.builder()
                .sellerId(SELLER_ID)
                .schoolId(1L)
                .categoryId(1L)
                .title("一致性测试商品 " + UUID.randomUUID().toString().substring(0, 8))
                .description("阶段 4 数据一致性回归测试商品")
                .price(new BigDecimal("199.00"))
                .originalPrice(new BigDecimal("399.00"))
                .conditionLevel("95新")
                .status(status)
                .location("图书馆一楼")
                .viewCount(viewCount)
                .createdTime(now)
                .updatedTime(now)
                .build();
        goodsMapper.insert(goods);
        createdGoodsIds.add(goods.getId());
        return goods.getId();
    }

    /** 直接落一条 COMPLETED 订单（不触发业务状态机），供评价外键依赖使用。 */
    private TradeOrder createCompletedOrder(Long goodsId) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = TradeOrder.builder()
                .orderNo("ORD_INTG_" + UUID.randomUUID().toString().substring(0, 8))
                .buyerId(BUYER_ID)
                .sellerId(SELLER_ID)
                .goodsId(goodsId)
                .goodsTitleSnapshot("一致性测试商品")
                .goodsPriceSnapshot(new BigDecimal("199.00"))
                .meetLocation("图书馆一楼")
                .orderStatus(OrderStatus.COMPLETED)
                .completedTime(now)
                .createdTime(now)
                .updatedTime(now)
                .build();
        tradeOrderMapper.insert(order);
        return order;
    }

    private Review createVisibleReview(Long orderId, Long goodsId, int score) {
        LocalDateTime now = LocalDateTime.now();
        Review review = Review.builder()
                .orderId(orderId)
                .goodsId(goodsId)
                .reviewerId(BUYER_ID)
                .reviewedUserId(SELLER_ID)
                .score(score)
                .content("一致性回归测试评价")
                .status(ReviewStatus.VISIBLE)
                .isAnonymous(false)
                .likeCount(0)
                .createdTime(now)
                .updatedTime(now)
                .build();
        reviewMapper.insert(review);
        createdReviewIds.add(review.getId());
        return review;
    }

    private void resetCreditState(Long userId) {
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));
    }

    private int creditScoreOf(Long userId) {
        UserCredit credit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));
        assertNotNull(credit, "信用档案必须存在: userId=" + userId);
        return credit.getCreditScore();
    }

    private int ledgerSumOf(Long userId) {
        List<UserCreditLog> logs = userCreditLogMapper.selectList(
                new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        return logs.stream().mapToInt(UserCreditLog::getChangeScore).sum();
    }

    // =========================================================================
    // 场景 1：并发下单锁货 + 浏览量同步
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. 并发「下单锁货 + 浏览量同步」后：status 仍为 LOCKED、view_count 不减少")
    void test01_concurrentOrderLockAndViewCountSync() throws Exception {
        int rounds = 5;
        int baseViews = 1000;
        int pendingDelta = 30;

        for (int round = 0; round < rounds; round++) {
            Long goodsId = createGoods("ON_SALE", baseViews);
            String viewKey = RedisKeyConstants.goodsViewKey(goodsId);
            stringRedisTemplate.opsForValue().set(viewKey, String.valueOf(pendingDelta));
            stringRedisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);
            AtomicReference<String> orderOutcome = new AtomicReference<>("NOT_EXECUTED");
            AtomicReference<String> syncOutcome = new AtomicReference<>("NOT_EXECUTED");

            executor.submit(() -> {
                try {
                    startLatch.await();
                    orderService.createOrder(BUYER_ID, goodsId, "图书馆一楼", "并发下单锁货");
                    orderOutcome.set("SUCCESS");
                } catch (Exception e) {
                    orderOutcome.set("FAILED: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startLatch.await();
                    goodsService.syncViewCounts();
                    syncOutcome.set("SUCCESS");
                } catch (Exception e) {
                    syncOutcome.set("FAILED: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "并发下单与浏览量同步必须在 20 秒内完成");
            executor.shutdown();

            assertEquals("SUCCESS", orderOutcome.get(), "下单锁货必须成功: " + orderOutcome.get());
            assertEquals("SUCCESS", syncOutcome.get(), "浏览量同步必须成功: " + syncOutcome.get());

            Goods after = goodsMapper.selectById(goodsId);
            assertNotNull(after);
            // 整行回写缺陷的典型症状：浏览量同步把订单刚写入的 LOCKED 覆盖回 ON_SALE
            assertEquals("LOCKED", after.getStatus(),
                    "并发浏览量同步绝不能把订单锁定的商品状态回退（第 " + (round + 1) + " 轮）");
            // 整行回写缺陷的另一个症状：下单锁货把读取时刻的旧 view_count 覆盖回去
            assertEquals(baseViews + pendingDelta, after.getViewCount(),
                    "并发下单锁货绝不能把浏览量同步结果回退（第 " + (round + 1) + " 轮）");
            assertEquals(0, Integer.parseInt(String.valueOf(stringRedisTemplate.opsForValue().get(viewKey))),
                    "Redis 浏览量增量应全部落盘并扣减归零");
            assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                            .isMember(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString())),
                    "落盘成功后该商品不应再留在脏集合中");

            System.out.printf("[场景1 第%d轮] goodsId=%d status=%s view_count=%d (期望 LOCKED/%d)%n",
                    round + 1, goodsId, after.getStatus(), after.getViewCount(), baseViews + pendingDelta);
        }
    }

    // =========================================================================
    // 场景 2：浏览量刷盘写库失败一次后重试
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("2. 浏览量刷盘写库失败一次后重试：增量不丢失、且不重复累加")
    void test02_viewCountSyncFailureThenRetry_noLossNoDuplication() {
        int baseViews = 100;
        int pendingDelta = 17;

        Long goodsId = createGoods("ON_SALE", baseViews);
        String viewKey = RedisKeyConstants.goodsViewKey(goodsId);
        stringRedisTemplate.opsForValue().set(viewKey, String.valueOf(pendingDelta));
        stringRedisTemplate.opsForSet().add(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString());

        installGoodsUpdateFailureTrigger(goodsId);
        try {
            // 第一次同步：数据库写入被强制失败
            goodsService.syncViewCounts();

            assertEquals(baseViews, goodsMapper.selectById(goodsId).getViewCount(),
                    "写库失败时数据库浏览量不能被修改");
            assertEquals(String.valueOf(pendingDelta), stringRedisTemplate.opsForValue().get(viewKey),
                    "写库失败后 Redis 增量必须被保留（旧实现 pop 后即扣减，增量永久丢失）");
            assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                            .isMember(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString())),
                    "写库失败后商品 ID 必须被放回脏集合，等待下次重试");
            System.out.printf("[场景2] 首次同步（DB 写失败）后：view_count=%d, redisDelta=%s, dirty=%s%n",
                    goodsMapper.selectById(goodsId).getViewCount(),
                    stringRedisTemplate.opsForValue().get(viewKey),
                    stringRedisTemplate.opsForSet().isMember(RedisKeyConstants.GOODS_VIEW_DIRTY_IDS, goodsId.toString()));
        } finally {
            dropGoodsUpdateFailureTrigger();
        }

        // 第二次同步：恢复正常后增量必须完整落盘且只落一次
        goodsService.syncViewCounts();
        assertEquals(baseViews + pendingDelta, goodsMapper.selectById(goodsId).getViewCount(),
                "重试后增量必须完整落盘（不丢失）");
        assertEquals(0, Integer.parseInt(String.valueOf(stringRedisTemplate.opsForValue().get(viewKey))),
                "落盘成功后 Redis 增量必须扣减归零");

        // 第三次同步：不得重复累加
        goodsService.syncViewCounts();
        assertEquals(baseViews + pendingDelta, goodsMapper.selectById(goodsId).getViewCount(),
                "同一次增量在重复执行下不得重复落盘");
        System.out.printf("[场景2] 重试落盘后：view_count=%d (期望 %d), redisDelta=%s%n",
                goodsMapper.selectById(goodsId).getViewCount(), baseViews + pendingDelta,
                stringRedisTemplate.opsForValue().get(viewKey));
    }

    private void installGoodsUpdateFailureTrigger(Long goodsId) {
        jdbcTemplate.execute(
                "CREATE OR REPLACE FUNCTION campus_trade.test_simulate_goods_update_failure() RETURNS trigger AS $$ " +
                        "BEGIN IF NEW.id = " + goodsId + " THEN " +
                        "RAISE EXCEPTION 'simulated database write failure for view-count sync test'; " +
                        "END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_simulate_goods_update_failure ON campus_trade.goods");
        jdbcTemplate.execute(
                "CREATE TRIGGER trg_test_simulate_goods_update_failure BEFORE UPDATE ON campus_trade.goods " +
                        "FOR EACH ROW EXECUTE FUNCTION campus_trade.test_simulate_goods_update_failure()");
    }

    private void dropGoodsUpdateFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_simulate_goods_update_failure ON campus_trade.goods");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS campus_trade.test_simulate_goods_update_failure()");
    }

    // =========================================================================
    // 场景 3：并发处理同一举报工单
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("3. 并发处理同一举报工单：只有一次生效，admin_audit_log 只新增一条")
    void test03_concurrentReportHandling_onlyOneTakesEffect() throws Exception {
        Long goodsId = createGoods("ON_SALE", 50);

        Report report = Report.builder()
                .reporterId(BUYER_ID)
                .targetType("GOODS")
                .targetId(goodsId)
                .reasonType("FRAUD")
                .description("并发治理回归测试")
                .status("PENDING")
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        reportMapper.insert(report);

        HandleReportRequest request = HandleReportRequest.builder()
                .action("VALID")
                .note("并发处理：举报属实，执行下架")
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    adminGovernanceService.handleReport(ADMIN_ID, ADMIN_USERNAME, report.getId(), request, "127.0.0.1");
                    successCount.incrementAndGet();
                    outcomes.add("SUCCESS");
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    String code = (e instanceof BusinessException be) ? String.valueOf(be.getCode()) : "NON_BUSINESS";
                    outcomes.add("FAILED(code=" + code + "): " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "并发处理工单必须在 20 秒内完成");
        executor.shutdown();

        assertEquals(1, successCount.get(), "并发处理同一工单必须有且仅有 1 次生效，实际结果: " + outcomes);
        assertEquals(1, failureCount.get(), "另一个管理员必须被明确拒绝，实际结果: " + outcomes);

        Report finalReport = reportMapper.selectById(report.getId());
        assertEquals("HANDLED_VALID", finalReport.getStatus(), "工单最终状态必须为 HANDLED_VALID");

        Integer reportAuditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM campus_trade.admin_audit_log WHERE target_type = 'REPORT' AND target_id = ?",
                Integer.class, report.getId());
        assertEquals(1, reportAuditRows, "并发处理同一工单时审计流水只能新增一条");

        assertEquals("OFF_SHELF", goodsMapper.selectById(goodsId).getStatus(),
                "治理动作（下架）必须只执行一次且生效");

        System.out.printf("[场景3] 并发处理结果: %s | 工单状态=%s | 审计条数=%d%n",
                String.join(" / ", outcomes), finalReport.getStatus(), reportAuditRows);
    }

    // =========================================================================
    // 场景 4：信用对账 + 「屏蔽 → 恢复 → 再次屏蔽」
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("4. 信用对账与三次治理动作：屏蔽→恢复→再次屏蔽 必须各自生效")
    void test04_creditReconciliation_shieldRestoreShieldCycle() {
        Long goodsId = createGoods("SOLD", 0);
        TradeOrder order = createCompletedOrder(goodsId);
        Review review = createVisibleReview(order.getId(), goodsId, 5);

        resetCreditState(SELLER_ID);
        UserCredit credit = creditService.getOrCreateCredit(SELLER_ID);
        assertEquals(100, credit.getCreditScore());

        // 1) 评价产生的 +3（模拟评价领域事件联动）
        creditService.addCredit(SELLER_ID, 3, CreditChangeType.REVIEW_GOOD, "REVIEW", review.getId(), "获得5星交易好评");
        assertEquals(103, creditScoreOf(SELLER_ID));

        // 2) 第一次屏蔽：追缴 3 分
        handleReviewReport(review.getId(), BUYER_ID, "屏蔽追缴：违规好评第一次");
        assertEquals(ReviewStatus.AUDIT_REJECTED, reviewMapper.selectById(review.getId()).getStatus());
        assertEquals(100, creditScoreOf(SELLER_ID), "第一次屏蔽必须追缴 3 分");

        // 3) 恢复展示：补回 3 分
        adminGovernanceService.restoreReview(ADMIN_ID, ADMIN_USERNAME, review.getId(), "复核后恢复展示", "127.0.0.1");
        assertEquals(ReviewStatus.VISIBLE, reviewMapper.selectById(review.getId()).getStatus());
        assertEquals(103, creditScoreOf(SELLER_ID), "恢复展示必须补回 3 分");

        // 4) 再次屏蔽：必须再次追缴（旧实现此处因幂等键与第一次完全相同而被静默拦截，追缴失效）
        handleReviewReport(review.getId(), THIRD_PARTY_ID, "屏蔽追缴：违规好评第二次");
        assertEquals(ReviewStatus.AUDIT_REJECTED, reviewMapper.selectById(review.getId()).getStatus());
        int finalScore = creditScoreOf(SELLER_ID);
        assertEquals(100, finalScore, "第二次屏蔽必须再次生效并追缴 3 分");

        // 5) 对账恒等式：主档余额 == 100 + 流水实际变动值之和
        int ledgerSum = ledgerSumOf(SELLER_ID);
        assertEquals(100 + ledgerSum, finalScore,
                "信用主档必须与审计流水严格对账（change_score 必须是实际生效值）");
        assertEquals(0, finalScore - (100 + ledgerSum));

        long shieldAdjustLogs = countCreditLogs(SELLER_ID, "ADMIN_ADJUST", "REVIEW", review.getId());
        long restoreLogs = countCreditLogs(SELLER_ID, "ADMIN_ADJUST", "REVIEW_RESTORE", review.getId());
        assertEquals(2L, shieldAdjustLogs, "两次屏蔽必须各产生一条追缴流水");
        assertEquals(1L, restoreLogs, "一次恢复必须产生一条补偿流水");

        // 6) 幂等语义：重放"同一次治理动作"（相同 actionKey）不得再次生效
        Long latestShieldAuditId = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM campus_trade.admin_audit_log WHERE target_type = 'REVIEW' AND target_id = ? "
                        + "AND operation_type = 'SHIELD_REVIEW'", Long.class, review.getId());
        assertNotNull(latestShieldAuditId);
        String sameActionKey = "SHIELD_REVIEW@AUDIT:" + latestShieldAuditId;

        creditService.deductCredit(SELLER_ID, 3, CreditChangeType.ADMIN_ADJUST, "REVIEW", review.getId(),
                "重放同一次治理动作（必须被幂等拦截）", sameActionKey);

        assertEquals(finalScore, creditScoreOf(SELLER_ID), "同一次治理动作重放不得重复扣分");
        assertEquals(2L, countCreditLogs(SELLER_ID, "ADMIN_ADJUST", "REVIEW", review.getId()),
                "同一次治理动作重放不得新增流水");

        // 7) 原始请求值留痕：被区间边界截断时 change_score 记录实际值、request_score 记录请求值
        resetCreditState(FRESH_CREDIT_USER_ID);
        UserCredit fresh = creditService.getOrCreateCredit(FRESH_CREDIT_USER_ID);
        assertEquals(100, fresh.getCreditScore());
        for (int i = 0; i < 40; i++) {
            creditService.addCredit(FRESH_CREDIT_USER_ID, 3, CreditChangeType.TRADE_COMPLETED, "ORDER",
                    9300000L + i, "边界截断对账测试");
        }
        UserCreditLog truncatedLog = userCreditLogMapper.selectList(
                        new LambdaQueryWrapper<UserCreditLog>()
                                .eq(UserCreditLog::getUserId, FRESH_CREDIT_USER_ID)
                                .orderByDesc(UserCreditLog::getId))
                .stream().findFirst().orElse(null);
        assertNotNull(truncatedLog);
        assertEquals(200, truncatedLog.getAfterScore(), "信用分封顶为 200");
        assertEquals(0, truncatedLog.getChangeScore(), "触及上边界时实际生效值必须为 0");
        assertEquals(3, truncatedLog.getRequestScore(), "原始请求值必须被完整留痕");
        assertEquals(200, creditScoreOf(FRESH_CREDIT_USER_ID));
        assertEquals(200, 100 + ledgerSumOf(FRESH_CREDIT_USER_ID),
                "存在区间截断时对账恒等式依然必须成立");

        System.out.printf("[场景4] 最终信用分=%d, 流水合计=%d, 追缴流水=%d条, 恢复流水=%d条%n",
                finalScore, ledgerSum, shieldAdjustLogs, restoreLogs);
    }

    private void handleReviewReport(Long reviewId, Long reporterId, String note) {
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType("REVIEW")
                .targetId(reviewId)
                .reasonType("MALICIOUS_REVIEW")
                .description(note)
                .status("PENDING")
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        reportMapper.insert(report);

        adminGovernanceService.handleReport(ADMIN_ID, ADMIN_USERNAME, report.getId(),
                HandleReportRequest.builder().action("VALID").note(note).build(), "127.0.0.1");
    }

    private long countCreditLogs(Long userId, String changeType, String relatedType, Long relatedId) {
        Long count = userCreditLogMapper.selectCount(new LambdaQueryWrapper<UserCreditLog>()
                .eq(UserCreditLog::getUserId, userId)
                .eq(UserCreditLog::getChangeType, changeType)
                .eq(UserCreditLog::getRelatedType, relatedType)
                .eq(UserCreditLog::getRelatedId, relatedId));
        return count == null ? 0L : count;
    }

    // =========================================================================
    // 场景 5：并发首次初始化信用档案（PG 唯一键冲突不得演变成 500）
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("5. 并发首次初始化信用档案：不抛异常、只建一条、分值 100")
    void test05_concurrentCreditProfileInitialization() throws Exception {
        resetCreditState(FRESH_CREDIT_USER_ID);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    creditService.getOrCreateCredit(FRESH_CREDIT_USER_ID);
                } catch (Exception e) {
                    failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "并发初始化必须在 20 秒内完成");
        executor.shutdown();

        assertTrue(failures.isEmpty(), "并发初始化不得出现任何异常（旧实现唯一键冲突后事务 aborted → 500）: " + failures);

        Long rowCount = userCreditMapper.selectCount(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, FRESH_CREDIT_USER_ID));
        assertEquals(1L, rowCount, "并发初始化必须只创建一条信用档案");
        assertEquals(100, creditScoreOf(FRESH_CREDIT_USER_ID));
    }

    // =========================================================================
    // 场景 6：并发重复评价必须得到 409（唯一约束冲突的业务捕获）
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("6. 并发重复评价：只有一次成功，另一次必须得到 409 业务错误而不是 500")
    void test06_concurrentDuplicateReview_returns409() throws Exception {
        Long goodsId = createGoods("SOLD", 0);
        TradeOrder order = createCompletedOrder(goodsId);

        resetCreditState(SELLER_ID);
        creditService.getOrCreateCredit(SELLER_ID);

        CreateReviewRequest request = CreateReviewRequest.builder()
                .orderId(order.getId())
                .score(5)
                .content("并发重复评价回归测试")
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> failureCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reviewService.createReview(BUYER_ID, request);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failureCodes.add(e.getCode());
                } catch (Exception e) {
                    failureCodes.add(-1);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "并发评价必须在 20 秒内完成");
        executor.shutdown();

        assertEquals(1, successCount.get(), "并发重复评价必须有且仅有 1 次成功");
        assertEquals(1, failureCodes.size(), "另一次必须失败");
        assertEquals(409, failureCodes.get(0).intValue(),
                "重复评价必须返回 409 业务错误（唯一约束冲突不得冒泡成 500）");

        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, order.getId())
                .eq(Review::getReviewerId, BUYER_ID));
        assertEquals(1, reviews.size(), "同一订单同一评价人只允许存在一条评价");
        createdReviewIds.add(reviews.get(0).getId());
    }
}
