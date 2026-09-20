package com.campustrade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.entity.UserCredit;
import com.campustrade.entity.UserCreditLog;
import com.campustrade.enums.CreditChangeType;
import com.campustrade.enums.CreditLevel;
import com.campustrade.mapper.UserCreditLogMapper;
import com.campustrade.mapper.UserCreditMapper;
import com.campustrade.service.CreditService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 5-B: 信用领域数据库迁移与模型落地实现自动化测试套件
 * 严格覆盖要求的 10 项核心指标：
 * 数据库：
 * 1. Flyway成功升级到V5
 * 2. user_credit新增字段存在 (completed_count, cancel_count, credit_level, updated_time)
 * 3. user_credit_log存在且包含唯一索引
 * 业务：
 * 4. 首次获取信用：score=100, level=GOOD
 * 5. 增加积分：100 + 2，结果：102，流水生成
 * 6. 扣除积分：102 - 5，结果：97，等级：FAIR
 * 7. 重复相同业务流水：积分不能重复变化 (幂等拦截)
 * 8. 积分上限：198 + 10，结果：200
 * 9. 积分下限：2 - 10，结果：0
 * 10. 事务异常回滚：user_credit和user_credit_log必须同时失败或成功
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CampusTradeStage5BTests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserCreditMapper userCreditMapper;

    @Autowired
    private UserCreditLogMapper userCreditLogMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final long TEST_USER_ID = 88880001L;

    @Test
    @Order(1)
    @DisplayName("1. 验证 Flyway 成功应用 V5__upgrade_credit_domain.sql 且当前版本升至 5")
    void test01_flyway_v5_applied_successfully() {
        assertNotNull(flyway, "Flyway 必须正确注入");

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 5, "至少已应用 5 个 Flyway migrations");

        // 验证 V5 存在且状态为 SUCCESS
        boolean v5Found = Arrays.stream(applied).anyMatch(m ->
                "5".equals(m.getVersion().getVersion()) && m.getState() == MigrationState.SUCCESS
        );
        assertTrue(v5Found, "Flyway 迁移版本 V5 必须成功应用且状态为 SUCCESS");

        MigrationInfo v5 = Arrays.stream(applied)
                .filter(m -> "5".equals(m.getVersion().getVersion()))
                .findFirst()
                .orElse(null);
        assertNotNull(v5, "Flyway 迁移版本 V5 必须存在");
        assertEquals("5", v5.getVersion().getVersion(), "V5 版本号必须为 5");
        assertEquals("upgrade credit domain", v5.getDescription(), "V5 描述必须为 upgrade credit domain");
    }

    @Test
    @Order(2)
    @DisplayName("2. 验证 campus_trade.user_credit 扩展字段及 CHECK 约束存在")
    void test02_user_credit_expanded_columns_exist() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'campus_trade' AND table_name = 'user_credit'",
                String.class
        );

        assertTrue(columns.contains("completed_count"), "user_credit 必须包含 completed_count 字段");
        assertTrue(columns.contains("cancel_count"), "user_credit 必须包含 cancel_count 字段");
        assertTrue(columns.contains("credit_level"), "user_credit 必须包含 credit_level 字段");
        assertTrue(columns.contains("updated_time"), "user_credit 必须包含 updated_time 字段");

        // 验证约束
        Integer checkConstraintCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.check_constraints WHERE constraint_name = 'chk_user_credit_score_range'",
                Integer.class
        );
        assertNotNull(checkConstraintCount);
        assertTrue(checkConstraintCount > 0, "chk_user_credit_score_range 积分范围约束必须生效");
    }

    @Test
    @Order(3)
    @DisplayName("3. 验证 campus_trade.user_credit_log 数据表与索引结构完整性")
    void test03_user_credit_log_table_and_indexes_exist() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'campus_trade' AND table_name = 'user_credit_log'",
                Integer.class
        );
        assertEquals(1, tableCount, "user_credit_log 数据表必须存在");

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'campus_trade' AND table_name = 'user_credit_log'",
                String.class
        );

        List<String> requiredColumns = List.of(
                "id",
                "user_id",
                "change_type",
                "change_score",
                "before_score",
                "after_score",
                "related_type",
                "related_id",
                "reason",
                "created_time"
        );
        for (String col : requiredColumns) {
            assertTrue(columns.contains(col), "user_credit_log 必须包含字段: " + col);
        }

        // 验证唯一幂等索引 uk_credit_log_idempotent
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'campus_trade' AND tablename = 'user_credit_log' AND indexname = 'uk_credit_log_idempotent'",
                Integer.class
        );
        assertNotNull(indexCount);
        assertEquals(1, indexCount, "uk_credit_log_idempotent 幂等唯一索引必须存在");
    }

    @Test
    @Order(4)
    @DisplayName("4. 首次获取信用档案：默认 score=100, level=GOOD, 计数均为 0")
    void test04_first_get_or_create_credit() {
        // 清理测试用户历史数据
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, TEST_USER_ID));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, TEST_USER_ID));

        UserCredit credit = creditService.getOrCreateCredit(TEST_USER_ID);
        assertNotNull(credit);
        assertEquals(TEST_USER_ID, credit.getUserId());
        assertEquals(100, credit.getCreditScore(), "初始信用分必须为 100");
        assertEquals("GOOD", credit.getCreditLevel(), "初始信用等级必须为 GOOD");
        assertEquals(0, credit.getTradeCount());
        assertEquals(0L, credit.getCompletedCount());
        assertEquals(0L, credit.getCancelCount());
    }

    @Test
    @Order(5)
    @DisplayName("5. 增加积分：100 + 2 -> 102，并成功生成审计流水")
    void test05_add_credit_generates_log() {
        long orderId = 2026091701L;

        UserCredit updated = creditService.addCredit(
                TEST_USER_ID,
                2,
                CreditChangeType.TRADE_COMPLETED,
                "ORDER",
                orderId,
                "订单顺利完成履约加分"
        );

        assertNotNull(updated);
        assertEquals(102, updated.getCreditScore(), "增加2分后积分必须为 102");
        assertEquals("GOOD", updated.getCreditLevel());
        assertEquals(1, updated.getTradeCount());
        assertEquals(1L, updated.getCompletedCount());

        // 验证流水记录
        List<UserCreditLog> logs = userCreditLogMapper.selectList(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, TEST_USER_ID)
                        .eq(UserCreditLog::getRelatedType, "ORDER")
                        .eq(UserCreditLog::getRelatedId, orderId)
        );

        assertEquals(1, logs.size(), "必须生成 1 条流水日志");
        UserCreditLog creditLog = logs.get(0);
        assertEquals("TRADE_COMPLETED", creditLog.getChangeType());
        assertEquals(2, creditLog.getChangeScore());
        assertEquals(100, creditLog.getBeforeScore());
        assertEquals(102, creditLog.getAfterScore());
        assertEquals("订单顺利完成履约加分", creditLog.getReason());
    }

    @Test
    @Order(6)
    @DisplayName("6. 扣除积分：102 - 5 -> 97，等级自动下调至 FAIR")
    void test06_deduct_credit_changes_level_to_fair() {
        long orderId = 2026091702L;

        UserCredit updated = creditService.deductCredit(
                TEST_USER_ID,
                5,
                CreditChangeType.TRADE_CANCEL_PENALTY,
                "ORDER",
                orderId,
                "待面交阶段违约取消扣分"
        );

        assertNotNull(updated);
        assertEquals(97, updated.getCreditScore(), "102扣除5分后必须为 97");
        assertEquals("FAIR", updated.getCreditLevel(), "97分对应等级必须自动调整为 FAIR");
        assertEquals(1L, updated.getCancelCount(), "违约取消次数必须+1");

        // 验证流水
        UserCreditLog creditLog = userCreditLogMapper.selectOne(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, TEST_USER_ID)
                        .eq(UserCreditLog::getChangeType, "TRADE_CANCEL_PENALTY")
                        .eq(UserCreditLog::getRelatedId, orderId)
        );
        assertNotNull(creditLog);
        assertEquals(-5, creditLog.getChangeScore());
        assertEquals(102, creditLog.getBeforeScore());
        assertEquals(97, creditLog.getAfterScore());
    }

    @Test
    @Order(7)
    @DisplayName("7. 重复相同业务流水：幂等拦截，积分不重复变化")
    void test07_duplicate_event_is_idempotent() {
        long orderId = 2026091701L; // 与 test05 相同

        // 重复调用加分
        UserCredit credit = creditService.addCredit(
                TEST_USER_ID,
                2,
                CreditChangeType.TRADE_COMPLETED,
                "ORDER",
                orderId,
                "重复回调订单加分"
        );

        // 积分依然应为 97，不应发生重复累加
        assertEquals(97, credit.getCreditScore(), "幂等请求下积分不能发生变化");

        // 验证该业务事件仍然只有 1 条日志
        Long count = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, TEST_USER_ID)
                        .eq(UserCreditLog::getRelatedType, "ORDER")
                        .eq(UserCreditLog::getRelatedId, orderId)
                        .eq(UserCreditLog::getChangeType, "TRADE_COMPLETED")
        );
        assertEquals(1L, count, "相同事件的流水记录不能重复产生");
    }

    @Test
    @Order(8)
    @DisplayName("8. 积分上限钳位：198 + 10 -> 200，不超出上限")
    void test08_credit_score_upper_bound() {
        long userId = 88880002L;
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));

        UserCredit credit = creditService.getOrCreateCredit(userId);
        credit.setCreditScore(198);
        credit.setCreditLevel(CreditLevel.EXCELLENT.name());
        userCreditMapper.updateById(credit);

        UserCredit updated = creditService.addCredit(
                userId,
                10,
                CreditChangeType.TRADE_COMPLETED,
                "ORDER",
                2026091703L,
                "积分上限测试"
        );

        assertEquals(200, updated.getCreditScore(), "积分上限必须被钳位在 200");
        assertEquals("EXCELLENT", updated.getCreditLevel());
    }

    @Test
    @Order(9)
    @DisplayName("9. 积分下限钳位：2 - 10 -> 0，不低于 0")
    void test09_credit_score_lower_bound() {
        long userId = 88880003L;
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));

        UserCredit credit = creditService.getOrCreateCredit(userId);
        credit.setCreditScore(2);
        credit.setCreditLevel(CreditLevel.POOR.name());
        userCreditMapper.updateById(credit);

        UserCredit updated = creditService.deductCredit(
                userId,
                10,
                CreditChangeType.TRADE_CANCEL_PENALTY,
                "ORDER",
                2026091704L,
                "积分下限测试"
        );

        assertEquals(0, updated.getCreditScore(), "积分下限必须被钳位在 0");
        assertEquals("POOR", updated.getCreditLevel());
    }

    @Test
    @Order(10)
    @DisplayName("10. 事务原子性验证：异常时 user_credit 与 user_credit_log 必须同时回滚")
    void test10_transactional_rollback() {
        long userId = 88880004L;
        userCreditLogMapper.delete(new LambdaQueryWrapper<UserCreditLog>().eq(UserCreditLog::getUserId, userId));
        userCreditMapper.delete(new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId));

        creditService.getOrCreateCredit(userId);

        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            // 在事务中调用 addCredit
            creditService.addCredit(
                    userId,
                    5,
                    CreditChangeType.TRADE_COMPLETED,
                    "ORDER",
                    2026091705L,
                    "事务测试"
            );

            // 模拟业务逻辑中途异常
            throw new RuntimeException("模拟业务失败异常");
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
        }

        // 验证主档回滚至 100 分
        UserCredit credit = userCreditMapper.selectOne(
                new LambdaQueryWrapper<UserCredit>().eq(UserCredit::getUserId, userId)
        );
        assertNotNull(credit);
        assertEquals(100, credit.getCreditScore(), "事务回滚后分数必须保持原样 (100)");

        // 验证流水未插入
        Long logCount = userCreditLogMapper.selectCount(
                new LambdaQueryWrapper<UserCreditLog>()
                        .eq(UserCreditLog::getUserId, userId)
                        .eq(UserCreditLog::getRelatedId, 2026091705L)
        );
        assertEquals(0L, logCount, "事务回滚后流水日志不能落库");
    }
}
