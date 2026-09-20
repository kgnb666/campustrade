package com.campustrade.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置类
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties minioProperties;

    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客户端: endpoint={}, bucket={}", minioProperties.getEndpoint(), minioProperties.getBucketName());
        MinioClient client = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();

        // 启动时检查并初始化默认存储桶与公共读取策略
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(minioProperties.getBucketName()).build()
            );
            if (!exists) {
                log.info("MinIO 存储桶 {} 不存在，正在自动创建...", minioProperties.getBucketName());
                client.makeBucket(
                        MakeBucketArgs.builder().bucket(minioProperties.getBucketName()).build()
                );
                log.info("MinIO 存储桶 {} 创建成功", minioProperties.getBucketName());
            }

            // 配置存储桶为公共只读，便于客户端直接获取图片资源
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
                          "Resource": ["arn:aws:s3:::%s"]
                        },
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(minioProperties.getBucketName(), minioProperties.getBucketName());

            client.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .config(policy)
                            .build()
            );
            log.info("MinIO 存储桶 {} 公共只读访问策略已就绪", minioProperties.getBucketName());
        } catch (Exception e) {
            log.warn("MinIO 存储桶自动初始化异常（请确认 MinIO 服务是否在线）: {}", e.getMessage());
        }

        return client;
    }
}
