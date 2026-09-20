package com.campustrade;

import com.campustrade.common.constant.RedisKeyConstants;
import com.campustrade.common.util.ClientIpUtils;
import com.campustrade.dto.RegisterRequestDTO;
import com.campustrade.support.TestCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可信代理解析的"已配置"分支回归测试。
 *
 * <h2>为什么单独一个测试类</h2>
 * <p>{@code security.trusted-proxies} 是启动期配置（{@link ClientIpUtils} 构造时解析一次），
 * 要在"已配置"的分支上断言，必须换一份应用配置 —— 因此这里用
 * {@code @SpringBootTest(properties = ...)} 起第二个上下文；其余默认配置的行为
 * 由 {@code CampusTradeFinalAuditHardenTests} 覆盖。</p>
 *
 * <p>配置的内容刻意同时包含"精确 IP"与"CIDR"，并且包含 10.0.0.0/8 用于验证
 * "从右向左跳过可信代理跳"的规则。</p>
 */
@SpringBootTest(properties = "security.trusted-proxies=127.0.0.1/32,10.0.0.0/8")
@AutoConfigureMockMvc
class TrustedProxyIpResolutionTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ClientIpUtils clientIpUtils;

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("1. 配置可信代理后：remoteAddr∈可信网段时按 X-Forwarded-For 解析限流维度（贴出 Redis 键）")
    void test01_forwardedHeaderHonoredBehindTrustedProxy() throws Exception {
        String clientIp = "203.0.113.7";
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(clientIp));
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey("127.0.0.1"));

        String username = "proxy_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequestDTO register = new RegisterRequestDTO();
        register.setUsername(username);
        register.setPassword(TestCredentials.randomPassword());
        register.setEmail(username + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .with(remoteAddr("127.0.0.1")) // 可信代理自身
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String count = stringRedisTemplate.opsForValue().get(RedisKeyConstants.registerIpKey(clientIp));
        System.out.println("[可信代理] remoteAddr=127.0.0.1（可信） + XFF=" + clientIp
                + " → Redis 键 " + RedisKeyConstants.registerIpKey(clientIp) + " = " + count);
        assertEquals("1", count,
                "remoteAddr 属于可信代理时，限流维度必须按 X-Forwarded-For 的真实客户端地址计算");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                        RedisKeyConstants.registerIpKey("127.0.0.1"))),
                "此时不应再把代理自身的地址当成客户端");

        // 清理：不要把配额留在共享的测试 Redis 里
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(clientIp));
    }

    @Test
    @DisplayName("2. remoteAddr 不在可信网段时仍不采信转发头（防止任意客户端自封代理）")
    void test02_untrustedRemoteAddrStillIgnoresHeader() throws Exception {
        String forgedIp = "198.51.100.9";
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey("172.20.0.9"));
        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey(forgedIp));

        String username = "proxy_untrusted_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequestDTO register = new RegisterRequestDTO();
        register.setUsername(username);
        register.setPassword(TestCredentials.randomPassword());
        register.setEmail(username + "@test.edu.cn");

        mockMvc.perform(post("/auth/register")
                        .with(remoteAddr("172.20.0.9")) // 不在 127.0.0.1/32 也不在 10.0.0.0/8
                        .header("X-Forwarded-For", forgedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());

        assertEquals("1", stringRedisTemplate.opsForValue().get(
                        RedisKeyConstants.registerIpKey("172.20.0.9")),
                "非可信来源仍然只能按 remoteAddr 计入限流维度");
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                RedisKeyConstants.registerIpKey(forgedIp))));

        stringRedisTemplate.delete(RedisKeyConstants.registerIpKey("172.20.0.9"));
    }

    @Test
    @DisplayName("3. 解析出的可信代理网段与配置一致（含 CIDR 归一化）")
    void test03_trustedProxyConfigurationIsLoaded() {
        assertEquals(2, clientIpUtils.getTrustedProxies().size(),
                "配置应解析出 2 个网段: " + clientIpUtils.getTrustedProxies());
        assertTrue(clientIpUtils.isTrustedProxy("127.0.0.1"));
        assertTrue(clientIpUtils.isTrustedProxy("10.255.255.254"));
        assertFalse(clientIpUtils.isTrustedProxy("11.0.0.1"));
        assertFalse(clientIpUtils.isTrustedProxy("203.0.113.7"));
    }
}
