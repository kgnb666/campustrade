package com.campustrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek AI 开放接口配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    /**
     * API 密钥 (默认从环境变量 DEEPSEEK_API_KEY 读取)
     */
    private String apiKey;

    /**
     * 接口基础地址 (默认 https://api.deepseek.com/v1)
     */
    private String baseUrl = "https://api.deepseek.com/v1";

    /**
     * 模型名称 (默认 deepseek-chat)
     */
    private String model = "deepseek-chat";

    /**
     * 请求超时时间 (毫秒，默认 15000ms)
     */
    private Integer timeout = 15000;
}
