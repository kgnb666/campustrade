package com.campustrade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /**
     * MinIO 服务端点 URL，例如 http://127.0.0.1:9000
     */
    private String endpoint = "http://127.0.0.1:9000";

    /**
     * 访问凭证 Access Key（由 MINIO_ROOT_USER 注入；源码中不保留任何可用默认值）
     */
    private String accessKey;

    /**
     * 访问凭证 Secret Key（由 MINIO_ROOT_PASSWORD 注入；源码中不保留任何可用默认值）
     */
    private String secretKey;

    /**
     * 存储桶名称
     */
    private String bucketName = "campustrade";

    /**
     * 外部公共访问前缀，例如 http://127.0.0.1:9000/campustrade
     */
    private String urlPrefix = "http://127.0.0.1:9000/campustrade";
}
