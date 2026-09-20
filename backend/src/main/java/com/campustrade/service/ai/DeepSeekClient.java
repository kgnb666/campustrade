package com.campustrade.service.ai;

import com.campustrade.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek AI 开放接口客户端
 * 负责底层 HTTP 交互、超时控制、耗时统计与异常拦截
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;

    /**
     * 占位符前缀：与 {@code .env.example} 和 {@code ProdSecretsGuard} 使用同一套"未填写"判定规则
     * （{@code CHANGE_ME_*}）。
     */
    private static final String PLACEHOLDER_PREFIX = "change_me";

    /**
     * 历史占位符字面量（曾是 application.yml 的默认值）。它从来没有指向过任何真实服务，
     * 但仍要继续被拒绝：若有人把它当成真实 Key 配置进来，必须走降级而不是真的发出请求。
     * 配置默认值现已改为 {@code CHANGE_ME_deepseek_api_key}（见 application.yml）。
     */
    private static final String LEGACY_PLACEHOLDER = "your_deepseek_api_key_here";

    /**
     * 判断配置里的 Key 是否是"没填"的占位符。
     */
    private static boolean isPlaceholder(String apiKey) {
        String normalized = apiKey.trim().toLowerCase();
        return normalized.startsWith(PLACEHOLDER_PREFIX) || normalized.equals(LEGACY_PLACEHOLDER);
    }

    /**
     * 发起对话补全请求并返回模型回复文本
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的文本内容 (通常为 JSON 字符串)
     * @throws Exception 请求失败时抛出异常
     */
    public String chatCompletion(String systemPrompt, String userPrompt) throws Exception {
        String apiKey = deepSeekProperties.getApiKey();
        String baseUrl = deepSeekProperties.getBaseUrl();
        String model = deepSeekProperties.getModel();
        int timeout = deepSeekProperties.getTimeout() != null ? deepSeekProperties.getTimeout() : 15000;

        if (!StringUtils.hasText(apiKey) || isPlaceholder(apiKey)) {
            log.warn("DeepSeek API Key 未配置或为占位符（CHANGE_ME_* / 历史默认值），触发优雅降级");
            throw new IllegalStateException("DeepSeek API Key 未有效配置");
        }

        long startTime = System.currentTimeMillis();
        log.info("开始调用 DeepSeek AI 接口: model=[{}], baseUrl=[{}]", model, baseUrl);

        // 构建请求报文 (OpenAI 兼容协议)
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.7);

        // 配置带超时的 RestClient
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey.trim())
                .defaultHeader("Content-Type", "application/json")
                .build();

        try {
            String rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("DeepSeek AI 接口调用成功, 耗时: [{}ms]", elapsed);

            // 解析 choices[0].message.content
            JsonNode rootNode = objectMapper.readTree(rawResponse);
            JsonNode choices = rootNode.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode messageNode = choices.get(0).path("message");
                String content = messageNode.path("content").asText();
                return content;
            }

            throw new IllegalStateException("DeepSeek 响应报文格式不符合预期: " + rawResponse);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            String errorMsg = e.getMessage();
            if (StringUtils.hasText(apiKey) && errorMsg != null) {
                errorMsg = errorMsg.replace(apiKey, "******");
            }
            log.error("DeepSeek AI 接口调用失败 (耗时: {}ms): {}", elapsed, errorMsg);
            throw e;
        }
    }
}
