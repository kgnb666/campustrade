package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.dto.CreateOrderDTO;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.UserCredit;
import com.campustrade.enums.OrderStatus;
import com.campustrade.exception.OrderBusinessException;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.service.OrderService;
import com.campustrade.service.order.OrderStateMachine;
import com.campustrade.enums.GoodsStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4-B-2: 订单领域核心模型与状态机自动化测试套件
 * 严格覆盖要求的 9 项核心指标与状态机流转约束:
 * 1. 买家不能买自己的商品
 * 2. 商品非 ON_SALE 状态不能下单
 * 3. 创建订单成功：goods 变 LOCKED，order 为 WAIT_SELLER_CONFIRM，快照字段正确
 * 4. 非卖家不能 confirmOrder
 * 5. confirmOrder 成功：order 变 WAIT_MEET
 * 6. 非买家且非卖家不能 cancelOrder
 * 7. cancelOrder 成功：order 变 CANCELLED，goods 恢复 ON_SALE
 * 8. completeOrder 成功：order 变 COMPLETED，goods 变 SOLD，双方 trade_count + 1
 * 9. 非法状态转换拦截（如直接从 WAIT_SELLER_CONFIRM 到 COMPLETED）
 * 10. 状态机全状态流转矩阵验证与终态不可逆约束
 * 11. 取消原因非空校验拦截
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage4B2Tests {

    @Autowired
    private OrderService orderService;

    @Autowired
    private TradeOrderMapper tradeOrderMapper;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private GoodsImageMapper goodsImageMapper;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long TEST_SELLER_ID = 4001L;
    private static final Long TEST_BUYER_ID = 4002L;
    private static final Long TEST_THIRD_PARTY_ID = 4003L;

    /**
     * 阶段 4 加固后 goods.seller_id / trade_order.buyer_id / trade_order.seller_id 均有外键约束
     * （见 V10__data_integrity_constraints.sql），测试夹具必须先落真实的用户行，
     * 否则插入商品/订单会因外键校验失败。此前这些 ID 只存在于内存中，是测试数据本身的悬空引用。
     */
    @BeforeEach
    void ensureReferencedUsersExist() {
        initUserIfAbsent(TEST_SELLER_ID, "stage4b2_seller");
        initUserIfAbsent(TEST_BUYER_ID, "stage4b2_buyer");
        initUserIfAbsent(TEST_THIRD_PARTY_ID, "stage4b2_third");
    }

    private void initUserIfAbsent(Long id, String username) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM campus_trade.\"user\" WHERE id = ?", Integer.class, id);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO campus_trade.\"user\" (id, username, password, nickname, role, status, created_time, updated_time) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', ?, ?)",
                id, username, "$2a$10$abcdefghijklmnopqrstuvwxyz1234567890", username, now, now);
    }

    private Long createTestGoods(String status, BigDecimal price) {
        Goods goods = Goods.builder()
                .sellerId(TEST_SELLER_ID)
                .schoolId(1L)
                .categoryId(1L)
                .title("Stage4 测试商品 iPad " + System.nanoTime())
                .description("自用九成新，无划痕")
                .price(price != null ? price : new BigDecimal("1999.00"))
                .originalPrice(new BigDecimal("3999.00"))
                .conditionLevel("9成新")
                .status(status != null ? status : GoodsStatus.ON_SALE.getCode())
                .location("学子第一食堂西门")
                .viewCount(10)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        goodsMapper.insert(goods);

        // 插入一张封面测试图
        GoodsImage image = GoodsImage.builder()
                .goodsId(goods.getId())
                .imageUrl("http://127.0.0.1:9000/campustrade/goods/test-cover.png")
                .sort(1)
                .createdTime(LocalDateTime.now())
                .build();
        goodsImageMapper.insert(image);

        return goods.getId();
    }

    @AfterEach
    void cleanup() {
        // 清理测试中产生的订单与商品
        jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE buyer_id IN (?, ?, ?) OR seller_id IN (?, ?, ?)",
                TEST_BUYER_ID, TEST_SELLER_ID, TEST_THIRD_PARTY_ID, TEST_BUYER_ID, TEST_SELLER_ID, TEST_THIRD_PARTY_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.goods_image WHERE goods_id IN (SELECT id FROM campus_trade.goods WHERE seller_id IN (?, ?, ?))",
                TEST_BUYER_ID, TEST_SELLER_ID, TEST_THIRD_PARTY_ID);
        jdbcTemplate.update("DELETE FROM campus_trade.goods WHERE seller_id IN (?, ?, ?)",
                TEST_BUYER_ID, TEST_SELLER_ID, TEST_THIRD_PARTY_ID);
    }

    @Test
    @Order(1)
    @DisplayName("1. 买家不能购买自己发布的商品")
    void test01_buyer_cannot_buy_own_goods() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("500.00"));

        CreateOrderDTO dto = CreateOrderDTO.builder()
                .goodsId(goodsId)
                .meetLocation("图书馆")
                .buyerMessage("我想买自己的东西")
                .build();

        OrderBusinessException ex = assertThrows(OrderBusinessException.class, () -> {
            orderService.createOrder(TEST_SELLER_ID, dto);
        });

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("买家不能购买自己发布的商品"));
    }

    @Test
    @Order(2)
    @DisplayName("2. 商品非 ON_SALE 状态不能下单 (如 LOCKED, SOLD, OFF_SHELF)")
    void test02_non_on_sale_goods_cannot_be_ordered() {
        // 测试 LOCKED
        Long lockedGoodsId = createTestGoods(GoodsStatus.LOCKED.getCode(), new BigDecimal("100.00"));
        OrderBusinessException ex1 = assertThrows(OrderBusinessException.class, () -> {
            orderService.createOrder(TEST_BUYER_ID, lockedGoodsId, "食堂", "想买已锁定的商品");
        });
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("商品非在售状态"));

        // 测试 SOLD
        Long soldGoodsId = createTestGoods(GoodsStatus.SOLD.getCode(), new BigDecimal("100.00"));
        OrderBusinessException ex2 = assertThrows(OrderBusinessException.class, () -> {
            orderService.createOrder(TEST_BUYER_ID, soldGoodsId, "食堂", "想买已售出的商品");
        });
        assertEquals(400, ex2.getCode());
        assertTrue(ex2.getMessage().contains("商品非在售状态"));

        // 测试 OFF_SHELF
        Long offShelfGoodsId = createTestGoods(GoodsStatus.OFF_SHELF.getCode(), new BigDecimal("100.00"));
        OrderBusinessException ex3 = assertThrows(OrderBusinessException.class, () -> {
            orderService.createOrder(TEST_BUYER_ID, offShelfGoodsId, "食堂", "想买已下架的商品");
        });
        assertEquals(400, ex3.getCode());
        assertTrue(ex3.getMessage().contains("商品非在售状态"));
    }

    @Test
    @Order(3)
    @DisplayName("3. 创建订单成功：goods 变 LOCKED，order 为 WAIT_SELLER_CONFIRM，快照字段正确")
    void test03_create_order_success_and_snapshots_verified() {
        BigDecimal price = new BigDecimal("888.88");
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), price);
        Goods originalGoods = goodsMapper.selectById(goodsId);

        CreateOrderDTO dto = CreateOrderDTO.builder()
                .goodsId(goodsId)
                .meetLocation("南门快递驿站")
                .buyerMessage("请问周六下午方便面交吗？")
                .build();

        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, dto);

        assertNotNull(order);
        assertNotNull(order.getId());
        assertTrue(order.getOrderNo().startsWith("ORD"), "订单号必须以 ORD 开头");
        assertEquals(OrderStatus.WAIT_SELLER_CONFIRM, order.getOrderStatus(), "初始状态必须为 WAIT_SELLER_CONFIRM");
        assertEquals(TEST_BUYER_ID, order.getBuyerId());
        assertEquals(TEST_SELLER_ID, order.getSellerId());
        assertEquals(goodsId, order.getGoodsId());
        assertEquals(originalGoods.getTitle(), order.getGoodsTitleSnapshot(), "商品标题快照必须一致");
        assertEquals(0, price.compareTo(order.getGoodsPriceSnapshot()), "商品价格快照必须一致");
        assertEquals("http://127.0.0.1:9000/campustrade/goods/test-cover.png", order.getGoodsImageSnapshot(), "商品主图快照必须一致");
        assertEquals("南门快递驿站", order.getMeetLocation());
        assertEquals("请问周六下午方便面交吗？", order.getBuyerMessage());

        // 验证数据库中 goods 状态同步变为 LOCKED
        Goods updatedGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.LOCKED.getCode(), updatedGoods.getStatus(), "商品状态必须同步变更为 LOCKED");
    }

    @Test
    @Order(4)
    @DisplayName("4. 非卖家不能 confirmOrder")
    void test04_non_seller_cannot_confirm_order() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("200.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "东门", "留言");

        // 1. 买家尝试 confirm
        OrderBusinessException ex1 = assertThrows(OrderBusinessException.class, () -> {
            orderService.confirmOrder(order.getId(), TEST_BUYER_ID);
        });
        assertEquals(403, ex1.getCode());
        assertTrue(ex1.getMessage().contains("只有卖家可以确认订单"));

        // 2. 第三方无关用户尝试 confirm
        OrderBusinessException ex2 = assertThrows(OrderBusinessException.class, () -> {
            orderService.confirmOrder(order.getId(), TEST_THIRD_PARTY_ID);
        });
        assertEquals(403, ex2.getCode());
        assertTrue(ex2.getMessage().contains("只有卖家可以确认订单"));
    }

    @Test
    @Order(5)
    @DisplayName("5. confirmOrder 成功：order 变 WAIT_MEET，confirmedTime 写入")
    void test05_confirm_order_success() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("350.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "操场", "准时到");

        TradeOrder confirmedOrder = orderService.confirmOrder(order.getId(), TEST_SELLER_ID);

        assertNotNull(confirmedOrder);
        assertEquals(OrderStatus.WAIT_MEET, confirmedOrder.getOrderStatus(), "状态必须变更为 WAIT_MEET");
        assertNotNull(confirmedOrder.getConfirmedTime(), "卖家确认时间必须记录");

        // 查库验证持久化
        TradeOrder dbOrder = tradeOrderMapper.selectById(order.getId());
        assertEquals(OrderStatus.WAIT_MEET, dbOrder.getOrderStatus());
        assertNotNull(dbOrder.getConfirmedTime());
    }

    @Test
    @Order(6)
    @DisplayName("6. 非买家且非卖家不能 cancelOrder")
    void test06_third_party_cannot_cancel_order() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("150.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "教学楼", "留言");

        OrderBusinessException ex = assertThrows(OrderBusinessException.class, () -> {
            orderService.cancelOrder(order.getId(), TEST_THIRD_PARTY_ID, "恶意取消");
        });

        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("只有买家或卖家可以取消订单"));
    }

    @Test
    @Order(7)
    @DisplayName("7. cancelOrder 成功：order 变 CANCELLED，goods 恢复 ON_SALE")
    void test07_cancel_order_success_and_goods_status_restored() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("600.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "宿舍楼下", "留言");

        // 确认 goods 当前是 LOCKED
        Goods lockedGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.LOCKED.getCode(), lockedGoods.getStatus());

        // 买家取消订单
        String reason = "临时有事，不方便面交了";
        TradeOrder cancelledOrder = orderService.cancelOrder(order.getId(), TEST_BUYER_ID, reason);

        assertNotNull(cancelledOrder);
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getOrderStatus(), "订单状态必须变更为 CANCELLED");
        assertEquals(TEST_BUYER_ID, cancelledOrder.getCancelledBy(), "取消人ID必须正确");
        assertEquals(reason, cancelledOrder.getCancelReason(), "取消原因必须正确记录");
        assertNotNull(cancelledOrder.getCancelledTime(), "取消时间必须记录");

        // 验证商品状态恢复为 ON_SALE
        Goods restoredGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.ON_SALE.getCode(), restoredGoods.getStatus(), "取消后商品状态必须恢复为 ON_SALE");
    }

    @Test
    @Order(8)
    @DisplayName("8. completeOrder 成功：order 变 COMPLETED，goods 变 SOLD，双方 trade_count + 1")
    void test08_complete_order_success_and_trade_count_incremented() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("1200.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "体育馆", "面交完成测试");

        // 卖家先确认接单: WAIT_SELLER_CONFIRM -> WAIT_MEET
        orderService.confirmOrder(order.getId(), TEST_SELLER_ID);

        // 记录双方初始 trade_count
        UserCredit buyerCreditBefore = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, TEST_BUYER_ID)
        );
        int buyerCountBefore = buyerCreditBefore != null && buyerCreditBefore.getTradeCount() != null ? buyerCreditBefore.getTradeCount() : 0;

        UserCredit sellerCreditBefore = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, TEST_SELLER_ID)
        );
        int sellerCountBefore = sellerCreditBefore != null && sellerCreditBefore.getTradeCount() != null ? sellerCreditBefore.getTradeCount() : 0;

        // 买家或卖家完成订单
        TradeOrder completedOrder = orderService.completeOrder(order.getId(), TEST_BUYER_ID);

        assertNotNull(completedOrder);
        assertEquals(OrderStatus.COMPLETED, completedOrder.getOrderStatus(), "订单状态必须变更为 COMPLETED");
        assertNotNull(completedOrder.getCompletedTime(), "完成时间必须记录");

        // 验证商品状态更新为 SOLD
        Goods soldGoods = goodsMapper.selectById(goodsId);
        assertEquals(GoodsStatus.SOLD.getCode(), soldGoods.getStatus(), "完成后商品状态必须更新为 SOLD");

        // 验证买家与卖家信用档案中的 trade_count 均 + 1
        UserCredit buyerCreditAfter = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, TEST_BUYER_ID)
        );
        assertNotNull(buyerCreditAfter);
        assertEquals(buyerCountBefore + 1, buyerCreditAfter.getTradeCount(), "买家 trade_count 必须 + 1");

        UserCredit sellerCreditAfter = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, TEST_SELLER_ID)
        );
        assertNotNull(sellerCreditAfter);
        assertEquals(sellerCountBefore + 1, sellerCreditAfter.getTradeCount(), "卖家 trade_count 必须 + 1");
    }

    @Test
    @Order(9)
    @DisplayName("9. 非法状态转换拦截（如直接从 WAIT_SELLER_CONFIRM 到 COMPLETED）")
    void test09_invalid_state_transition_prevented() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("300.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "二餐", "留言");

        // 此时订单处于 WAIT_SELLER_CONFIRM，尚未经卖家确认，直接尝试 completeOrder
        OrderBusinessException ex = assertThrows(OrderBusinessException.class, () -> {
            orderService.completeOrder(order.getId(), TEST_SELLER_ID);
        });

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("非法订单状态流转"), "必须提示非法状态流转");
    }

    @Test
    @Order(10)
    @DisplayName("10. 状态机全状态流转矩阵单元级验证与终态不可逆约束")
    void test10_state_machine_transition_matrix() {
        // 允许流转
        assertTrue(OrderStateMachine.canTransition(OrderStatus.WAIT_SELLER_CONFIRM, OrderStatus.WAIT_MEET));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.WAIT_SELLER_CONFIRM, OrderStatus.CANCELLED));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.WAIT_MEET, OrderStatus.COMPLETED));
        assertTrue(OrderStateMachine.canTransition(OrderStatus.WAIT_MEET, OrderStatus.CANCELLED));

        // 禁止非法流转
        assertFalse(OrderStateMachine.canTransition(OrderStatus.WAIT_SELLER_CONFIRM, OrderStatus.COMPLETED));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.WAIT_SELLER_CONFIRM, OrderStatus.WAIT_SELLER_CONFIRM));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.WAIT_MEET, OrderStatus.WAIT_SELLER_CONFIRM));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.WAIT_MEET, OrderStatus.WAIT_MEET));

        // 终态 COMPLETED 不能流向任何状态
        assertFalse(OrderStateMachine.canTransition(OrderStatus.COMPLETED, OrderStatus.WAIT_SELLER_CONFIRM));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.COMPLETED, OrderStatus.WAIT_MEET));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.COMPLETED, OrderStatus.CANCELLED));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.COMPLETED, OrderStatus.COMPLETED));

        // 终态 CANCELLED 不能流向任何状态
        assertFalse(OrderStateMachine.canTransition(OrderStatus.CANCELLED, OrderStatus.WAIT_SELLER_CONFIRM));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.CANCELLED, OrderStatus.WAIT_MEET));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.CANCELLED, OrderStatus.COMPLETED));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.CANCELLED, OrderStatus.CANCELLED));

        // null 处理
        assertFalse(OrderStateMachine.canTransition(null, OrderStatus.WAIT_MEET));
        assertFalse(OrderStateMachine.canTransition(OrderStatus.WAIT_MEET, null));
        assertFalse(OrderStateMachine.canTransition(null, null));
    }

    @Test
    @Order(11)
    @DisplayName("11. 取消订单时取消原因不能为空")
    void test11_cancel_order_reason_not_empty() {
        Long goodsId = createTestGoods(GoodsStatus.ON_SALE.getCode(), new BigDecimal("100.00"));
        TradeOrder order = orderService.createOrder(TEST_BUYER_ID, goodsId, "宿舍", "留言");

        // null 原因
        OrderBusinessException ex1 = assertThrows(OrderBusinessException.class, () -> {
            orderService.cancelOrder(order.getId(), TEST_BUYER_ID, null);
        });
        assertEquals(400, ex1.getCode());
        assertTrue(ex1.getMessage().contains("取消原因不能为空"));

        // 空白字符串原因
        OrderBusinessException ex2 = assertThrows(OrderBusinessException.class, () -> {
            orderService.cancelOrder(order.getId(), TEST_BUYER_ID, "   ");
        });
        assertEquals(400, ex2.getCode());
        assertTrue(ex2.getMessage().contains("取消原因不能为空"));
    }
}
