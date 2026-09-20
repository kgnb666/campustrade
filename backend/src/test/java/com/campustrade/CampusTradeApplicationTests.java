package com.campustrade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CampusTrade 后端基础工程与中间件连通性测试
 */
@SpringBootTest
class CampusTradeApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("1. 验证 Spring 上下文装配与启动状态")
    void contextLoads() {
        assertNotNull(dataSource, "数据源注入成功");
        assertNotNull(redisTemplate, "RedisTemplate 注入成功");
    }

    @Test
    @DisplayName("2. 验证 PostgreSQL 连通性与 campus_trade 模式生效")
    void testDatabaseConnectionAndSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_schema();")) {

            assertTrue(rs.next(), "查询返回结果集");
            String currentSchema = rs.getString(1);
            assertEquals("campus_trade", currentSchema, "默认当前 Schema 应为 campus_trade");
        }
    }

    @Test
    @DisplayName("3. 验证 Redis 连通性与读写操作")
    void testRedisConnection() {
        String testKey = "campustrade:health:test";
        String testValue = "ok_stage_0";

        redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(30));
        Object value = redisTemplate.opsForValue().get(testKey);

        assertEquals(testValue, value, "Redis 读写数据一致");
        redisTemplate.delete(testKey);
    }
}
