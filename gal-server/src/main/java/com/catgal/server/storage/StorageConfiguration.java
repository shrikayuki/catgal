/*
 * 阅读说明
 * 按存储配置创建 MinIO 客户端，凭据经环境配置注入。
 */
package com.catgal.server.storage;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "catgal.storage.enabled", havingValue = "true")
    public MinioClient minioClient(StorageProperties properties) {
        if (properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("MinIO application credentials are required when storage is enabled");
        }
        MinioClient client = MinioClient.builder().endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey()).build();
        client.setTimeout(5000, 15000, 15000);
        return client;
    }
}
