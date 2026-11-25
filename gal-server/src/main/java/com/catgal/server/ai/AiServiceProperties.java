/*
 * 阅读说明
 * Python 服务地址、HMAC 密钥和超时等配置绑定对象，值来自 Spring 外部配置。
 */
package com.catgal.server.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "catgal.ai.service")
public class AiServiceProperties {
    private String baseUrl = "http://127.0.0.1:8090";
    private String hmacSecret;
    private boolean persistenceEnabled;
    private boolean personalizationEnabled;
    private boolean longTermMemoryEnabled;
    private boolean sessionSummaryEnabled;
    private boolean memoryCandidatesEnabled;
    private boolean memoryVectorEnabled;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(75);
    private Duration streamTimeout = Duration.ofSeconds(90);
}
