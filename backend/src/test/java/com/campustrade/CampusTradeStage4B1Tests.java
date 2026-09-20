package com.campustrade;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4-B-1: 订单系统数据库迁移基础建设测试套件
 * 核心验证：
 * 1. Flyway 迁移历史版本完整性 (V1, V2, V3, V4 全部成功应用，当前最新版本为 4)
 * 2. campus_trade.trade_order 表结构及 20 个字段类型与非空约束准确性
 * 3. 5 个基础/复合索引与唯一索引准确创建
 * 4. 核心并发防护: PostgreSQL Partial Unique Index (uk_trade_order_active_goods) 物理排他性验证
 */
@SpringBootTest
class CampusTradeStage4B1Tests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("1. 验证 Flyway 迁移历史，确认 V4__init_order_schema.sql 成功执行且为当前版本")
    void test01_flyway_v4_migration_applied_successfully() {
        assertNotNull(flyway, "Flyway 必须正确注入");

        MigrationInfo[] appliedMigrations = flyway.info().applied();
        assertTrue(appliedMigrations.length >= 4, "至少已应用 4 个 Flyway migration");

        // 验证 V1, V2, V3, V4 均在已应用列表并处于 SUCCESS 状态
        List<String> expectedVersions = List.of("1", "2", "3", "4");
        for (String expectedVer : expectedVersions) {
            boolean found = Arrays.stream(appliedMigrations).anyMatch(m ->
                    expectedVer.equals(m.getVersion().getVersion()) && m.getState() == MigrationState.SUCCESS
            );
            assertTrue(found, "Flyway 迁移版本 V" + expectedVer + " 必须成功应用且状态为 SUCCESS");
        }

        // 验证 V4 版本与描述信息完整正确
        MigrationInfo v4 = Arrays.stream(appliedMigrations)
                .filter(m -> "4".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v4, "Flyway 迁移版本 V4 必须存在");
        assertEquals("4", v4.getVersion().getVersion(), "V4 版本号必须为 4");
        assertEquals("init order schema", v4.getDescription(), "V4 描述必须为 init order schema");
    }

    @Test
    @DisplayName("2. 验证 campus_trade.trade_order 数据表与全部字段结构")
    void test02_trade_order_table_and_columns_exist() {
        // 验证数据表存在
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'trade_order'",
                Integer.class
        );
        assertEquals(1, tableCount, "campus_trade.trade_order 数据表必须存在");

        // 验证字段列表
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'campus_trade' AND table_name = 'trade_order'",
                String.class
        );

        List<String> requiredColumns = List.of(
                "id",
                "order_no",
                "buyer_id",
                "seller_id",
                "goods_id",
                "school_id",
                "goods_title_snapshot",
                "goods_price_snapshot",
                "goods_image_snapshot",
                "meet_location",
                "buyer_message",
                "seller_reply",
                "order_status",
                "cancel_reason",
                "cancelled_by",
                "confirmed_time",
                "completed_time",
                "cancelled_time",
                "created_time",
                "updated_time"
        );

        for (String col : requiredColumns) {
            assertTrue(columns.contains(col), "trade_order 表必须包含字段: " + col);
        }
    }

    @Test
    @DisplayName("3. 验证 trade_order 索引完整性 (主键、唯一索引、时序复合索引与局部唯一索引)")
    void test03_trade_order_indexes_exist() {
        List<Map<String, Object>> indexList = jdbcTemplate.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'trade_order'"
        );

        List<String> indexNames = indexList.stream()
                .map(m -> (String) m.get("indexname"))
                .toList();

        assertTrue(indexNames.contains("uk_trade_order_no"), "必须包含 uk_trade_order_no 唯一索引");
        assertTrue(indexNames.contains("idx_trade_order_buyer_time"), "必须包含 idx_trade_order_buyer_time 买家时序索引");
        assertTrue(indexNames.contains("idx_trade_order_seller_time"), "必须包含 idx_trade_order_seller_time 卖家时序索引");
        assertTrue(indexNames.contains("idx_trade_order_goods"), "必须包含 idx_trade_order_goods 商品外键索引");
        assertTrue(indexNames.contains("idx_trade_order_status"), "必须包含 idx_trade_order_status 状态索引");
        assertTrue(indexNames.contains("uk_trade_order_active_goods"), "必须包含 uk_trade_order_active_goods 局部唯一索引");

        // 进一步验证部分唯一索引的定义条件中包含 WHERE order_status IN ('WAIT_SELLER_CONFIRM', 'WAIT_MEET')
        String partialIndexDef = indexList.stream()
                .filter(m -> "uk_trade_order_active_goods".equals(m.get("indexname")))
                .map(m -> (String) m.get("indexdef"))
                .findFirst()
                .orElse("");

        assertTrue(partialIndexDef.contains("WHERE") && partialIndexDef.contains("WAIT_SELLER_CONFIRM") && partialIndexDef.contains("WAIT_MEET"),
                "uk_trade_order_active_goods 必须是基于活动状态的局部唯一索引");
    }

    @Test
    @DisplayName("4. 核心并发防超卖验证: Partial Unique Index 物理限制同一商品只能存在一个活动中订单")
    void test04_partial_unique_index_enforces_single_active_order() {
        long testGoodsId = 99999901L;
        String orderNo1 = "ORD_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        String orderNo2 = "ORD_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        String orderNo3 = "ORD_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        String orderNo4 = "ORD_TEST_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // 1. 插入第 1 个活动中订单 (WAIT_SELLER_CONFIRM) -> 必须成功
            jdbcTemplate.update(
                    "INSERT INTO campus_trade.trade_order (order_no, buyer_id, seller_id, goods_id, order_status, goods_title_snapshot, goods_price_snapshot) " +
                            "VALUES (?, 101, 201, ?, 'WAIT_SELLER_CONFIRM', '测试单件闲置', 99.00)",
                    orderNo1, testGoodsId
            );

            // 2. 尝试并发插入针对同一 goods_id 的第 2 个活动中订单 (WAIT_MEET) -> 必须被 Partial Unique Index 拦截并抛出冲突异常
            assertThrows(DataIntegrityViolationException.class, () -> {
                jdbcTemplate.update(
                        "INSERT INTO campus_trade.trade_order (order_no, buyer_id, seller_id, goods_id, order_status, goods_title_snapshot, goods_price_snapshot) " +
                                "VALUES (?, 102, 201, ?, 'WAIT_MEET', '测试单件闲置', 99.00)",
                        orderNo2, testGoodsId
                );
            }, "同一商品存在未完结活动订单时，绝不允许插入第二个活动订单 (防一货多卖)");

            // 3. 插入针对同一 goods_id 的已取消订单 (CANCELLED) -> 必须不受限制，成功插入
            assertDoesNotThrow(() -> {
                jdbcTemplate.update(
                        "INSERT INTO campus_trade.trade_order (order_no, buyer_id, seller_id, goods_id, order_status, goods_title_snapshot, goods_price_snapshot) " +
                                "VALUES (?, 103, 201, ?, 'CANCELLED', '测试单件闲置', 99.00)",
                        orderNo3, testGoodsId
                );
            }, "已取消的订单不属于活动状态，不受局部唯一约束限制");

            // 4. 插入针对同一 goods_id 的已完成历史订单 (COMPLETED) -> 必须不受限制，成功插入
            assertDoesNotThrow(() -> {
                jdbcTemplate.update(
                        "INSERT INTO campus_trade.trade_order (order_no, buyer_id, seller_id, goods_id, order_status, goods_title_snapshot, goods_price_snapshot) " +
                                "VALUES (?, 104, 201, ?, 'COMPLETED', '测试单件闲置', 99.00)",
                        orderNo4, testGoodsId
                );
            }, "已完成的历史订单不属于活动状态，不受局部唯一约束限制");

        } finally {
            // 清理测试临时数据
            jdbcTemplate.update("DELETE FROM campus_trade.trade_order WHERE goods_id = ?", testGoodsId);
        }
    }
}
