package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.CreateOrderDTO;
import com.campustrade.dto.UpdateGoodsDTO;
import com.campustrade.entity.*;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.OrderStatus;
import com.campustrade.exception.BusinessException;
import com.campustrade.exception.OrderBusinessException;
import com.campustrade.mapper.*;
import com.campustrade.service.CreditService;
import com.campustrade.service.GoodsService;
import com.campustrade.service.OrderService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7-A: 核心并发锁与数据一致性加固测试套件
 * 核心验证：
 * 1. 订单状态机流转排他行锁 (FOR UPDATE) 互斥性验证：
 *    并发 completeOrder 与 cancelOrder 绝对互斥，仅有 1 个操作成功，另一个被安全拦截；
 * 2. 信用分并发累加防漂移验证：
 *    10 线程并发增减信用分，主档 score 与 credit_log 流水总和保持绝对一致（0 漂移）；
 * 3. 交易中/售出商品防篡改守卫验证：
 *    处于 LOCKED 或 SOLD 状态的商品禁止修改、删除或下架。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private GoodsImageMapper goodsImageMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long SELLER_ID = 7001L;
    private static final Long BUYER_ID = 7002L;
    private static final String SELLER_USERNAME = "stage7_seller";

    @BeforeEach
    void setup() {
        // 确保测试卖家用户存在
        User seller = userMapper.selectById(SELLER_ID);
        if (seller == null) {
            seller = User.builder()
                    .id(SELLER_ID)
                    .username(SELLER_USERNAME)
                    .password("password123")
                    .nickname("并发测试卖家")
                    .role("STUDENT")
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            userMapper.insert(seller);
        }

        User buyer = userMapper.selectById(BUYER_ID);
        if (buyer == null) {
            buyer = User.builder()
                    .id(BUYER_ID)
                    .username("stage7_buyer")
                    .password("password123")
                    .nickname("并发测试买家")
                    .role("STUDENT")
                    .status("ACTIVE")
                    .createdTime(LocalDateTime.now())
                    .updatedTime(LocalDateTime.now())
                    .build();
            userMapper.insert(buyer);
        }
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE buyer_id = ? OR seller_id = ?", BUYER_ID, SELLER_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.goods_image WHERE goods_id IN (SELECT id FROM campus_trade.goods WHERE seller_id = ?)", SELLER_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.goods WHERE seller_id = ?", SELLER_ID);
    }

    private Long createTestGoods(String status) {
        Goods goods = Goods.builder()
                .sellerId(SELLER_ID)
                .schoolId(1L)
                .categoryId(1L)
                .title("并发测试商品 " + System.nanoTime())
                .description("并发测试商品描述")
                .price(new BigDecimal("99.00"))
                .originalPrice(new BigDecimal("199.00"))
                .conditionLevel("9成新")
                .status(status != null ? status : "ON_SALE")
                .location("图书馆一楼")
                .viewCount(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);

        GoodsImage image = GoodsImage.builder()
                .goodsId(goods.getId())
                .imageUrl("http://127.0.0.1:9000/campustrade/goods/test.png")
                .sort(0)
                .createdTime(LocalDateTime.now())
                .build();
        goodsImageMapper.insert(image);

        return goods.getId();
    }

    @Test
    @Order(1)
    @DisplayName("1. 并发订单状态跃迁排他行锁验证: 买家取消 vs 卖家完成 互斥串行化")
    void test01_concurrentCompleteAndCancelOrder_serialized() throws Exception {
        // 创建订单并推至 WAIT_MEET 状态
        Long goodsId = createTestGoods("ON_SALE");
        TradeOrder order = orderService.createOrder(BUYER_ID, goodsId, "二餐门口", "请尽快接单");
        order = orderService.confirmOrder(order.getId(), SELLER_ID);
        assertEquals(OrderStatus.WAIT_MEET, order.getOrderStatus());

        final Long targetOrderId = order.getId();
        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        // 线程 1: 买家尝试取消订单
        executor.submit(() -> {
            try {
                startLatch.await();
                orderService.cancelOrder(targetOrderId, BUYER_ID, "并发取消测试");
                successCount.incrementAndGet();
                results.add("CANCEL_SUCCESS");
            } catch (OrderBusinessException e) {
                failureCount.incrementAndGet();
                results.add("CANCEL_FAILED: " + e.getMessage());
            } catch (Exception e) {
                failureCount.incrementAndGet();
                results.add("CANCEL_ERROR: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // 线程 2: 卖家尝试完成订单
        executor.submit(() -> {
            try {
                startLatch.await();
                orderService.completeOrder(targetOrderId, SELLER_ID);
                successCount.incrementAndGet();
                results.add("COMPLETE_SUCCESS");
            } catch (OrderBusinessException e) {
                failureCount.incrementAndGet();
                results.add("COMPLETE_FAILED: " + e.getMessage());
            } catch (Exception e) {
                failureCount.incrementAndGet();
                results.add("COMPLETE_ERROR: " + e.getMessage());
            } finally {
                doneLatch.countDown();
            }
        });

        // 同时放行
        startLatch.countDown();
        boolean completedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completedInTime, "并发操作必须在10秒内完成");
        assertEquals(1, successCount.get(), "在悲观锁保护下，两个互斥跃迁必须有且仅有 1 个成功！实际结果: " + results);
        assertEquals(1, failureCount.get(), "另一个互斥操作必须被状态机拦截失败！实际结果: " + results);

        // 验证数据库中订单最终状态必然为合法单态（COMPLETED 或 CANCELLED，绝不会出现中间态或脏态）
        TradeOrder finalOrder = tradeOrderMapper.selectById(targetOrderId);
        assertNotNull(finalOrder);
        assertTrue(
                finalOrder.getOrderStatus() == OrderStatus.COMPLETED || finalOrder.getOrderStatus() == OrderStatus.CANCELLED,
                "订单最终状态必须为 COMPLETED 或 CANCELLED，当前为: " + finalOrder.getOrderStatus()
        );
    }

    @Test
    @Order(2)
    @DisplayName("2. 并发信用分累加防漂移验证: 10 线程并发写入无丢失，流水与主档绝对相符")
    void test02_concurrentCreditAccumulation_noDrift() throws Exception {
        final Long testCreditUserId = 99991001L;

        // V12 起 user_credit.user_id 有外键（NOT VALID 只豁免历史行，新行照样校验）：
        // 被引用的用户行必须先真实存在，否则信用档案插入会被外键拒绝。
        if (userMapper.selectById(testCreditUserId) == null) {
            LocalDateTime now = LocalDateTime.now();
            userMapper.insert(User.builder()
                    .id(testCreditUserId)
                    .username("order_conc_credit_user")
                    .password(UUID.randomUUID().toString())
                    .nickname("并发信用测试用户")
                    .role("USER")
                    .status("ACTIVE")
                    .createdTime(now)
                    .updatedTime(now)
                    .build());
        }

        // 清理历史测试数据
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, testCreditUserId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testCreditUserId));

        // 初始化用户信用档案 (初始 100 分)
        UserCredit initialCredit = creditService.getOrCreateCredit(testCreditUserId);
        assertEquals(100, initialCredit.getCreditScore());

        int threadCount = 10;
        int scorePerTx = 2; // 每笔加 2 分
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final long relatedId = 50000L + i; // 保证每笔流水拥有唯一的 relatedId，规避业务幂等拦截
            executor.submit(() -> {
                try {
                    startLatch.await();
                    creditService.addCredit(
                            testCreditUserId,
                            scorePerTx,
                            CreditChangeType.TRADE_COMPLETED,
                            "ORDER",
                            relatedId,
                            "高并发信用累加测试"
                    );
                } catch (Exception e) {
                    fail("信用并发加分不应发生未捕获异常: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 同时触发
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "10 笔并发信用变动应在 10 秒内执行完毕");

        // 验证主档最终积分: 100 + 10 * 2 = 120
        UserCredit finalCredit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, testCreditUserId)
        );
        assertNotNull(finalCredit);
        assertEquals(120, finalCredit.getCreditScore(), "并发累加后的最终信用积分必须严格等于 120 (无丢失更新)");
        assertEquals(10L, finalCredit.getCompletedCount(), "履约成功次数必须增加 10");
        assertEquals(10, finalCredit.getTradeCount(), "交易次数必须增加 10");

        // 验证流水记录数与总计分值
        List<UserCreditLog> logs = userCreditLogMapper.selectList(
                new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, testCreditUserId)
        );
        assertEquals(10, logs.size(), "必须生成 10 条独立的审计流水日志");

        int totalLogScoreDelta = logs.stream().mapToInt(UserCreditLog::getChangeScore).sum();
        assertEquals(20, totalLogScoreDelta, "流水日志分值变更总和必须精确等于 20 (0 漂移)");

        // 验证流水连续性：最后一条流水的 afterScore 必须与 finalCredit 的 score 完全一致
        int maxAfterScore = logs.stream().mapToInt(UserCreditLog::getAfterScore).max().orElse(0);
        assertEquals(120, maxAfterScore, "流水中记录的最大 afterScore 必须与最终主档一致");
    }

    @Test
    @Order(3)
    @DisplayName("3. 状态守卫验证: LOCKED 与 SOLD 商品禁止修改、删除或下架")
    void test03_lockedAndSoldGoods_cannotBeModifiedOrDeleted() {
        // 设置当前线程认证为测试卖家
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(SELLER_USERNAME, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );

        // 1. 测试 LOCKED 商品
        Long lockedGoodsId = createTestGoods("LOCKED");

        UpdateGoodsDTO updateDTO = UpdateGoodsDTO.builder()
                .title("恶意篡改标题")
                .price(new BigDecimal("9999.00"))
                .build();

        // 尝试修改 LOCKED 商品
        BusinessException ex1 = assertThrows(BusinessException.class, () -> {
            goodsService.updateGoods(lockedGoodsId, updateDTO);
        });
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("商品处于交易中或已售出，禁止修改或删除"));

        // 尝试删除 LOCKED 商品
        BusinessException ex2 = assertThrows(BusinessException.class, () -> {
            goodsService.deleteGoods(lockedGoodsId);
        });
        assertEquals(400, ex2.getCode());
        assertTrue(ex2.getMessage().contains("商品处于交易中或已售出，禁止修改或删除"));

        // 尝试下架 LOCKED 商品
        BusinessException ex3 = assertThrows(BusinessException.class, () -> {
            goodsService.updateGoodsStatus(lockedGoodsId, "OFF_SHELF");
        });
        assertEquals(400, ex3.getCode());
        assertTrue(ex3.getMessage().contains("商品处于交易"));

        // 2. 测试 SOLD 商品
        Long soldGoodsId = createTestGoods("SOLD");

        // 尝试修改 SOLD 商品
        BusinessException ex4 = assertThrows(BusinessException.class, () -> {
            goodsService.updateGoods(soldGoodsId, updateDTO);
        });
        assertEquals(400, ex4.getCode());
        assertTrue(ex4.getMessage().contains("商品处于交易中或已售出，禁止修改或删除"));

        // 尝试删除 SOLD 商品
        BusinessException ex5 = assertThrows(BusinessException.class, () -> {
            goodsService.deleteGoods(soldGoodsId);
        });
        assertEquals(400, ex5.getCode());
        assertTrue(ex5.getMessage().contains("商品处于交易中或已售出，禁止修改或删除"));

        // 尝试上架 SOLD 商品
        BusinessException ex6 = assertThrows(BusinessException.class, () -> {
            goodsService.updateGoodsStatus(soldGoodsId, "ON_SALE");
        });
        assertEquals(400, ex6.getCode());
        assertTrue(ex6.getMessage().contains("商品已售出，禁止变更状态"));
    }
}
