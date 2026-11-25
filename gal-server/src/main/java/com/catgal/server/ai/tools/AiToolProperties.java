/*
 * 阅读说明
 * AI 只读业务工具开关、请求上限与内部认证相关配置。
 */
package com.catgal.server.ai.tools;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "catgal.ai.tools")
public class AiToolProperties {
    private boolean enabled;
    private boolean semanticSearchEnabled;
    private int requestMaxAgeSeconds = 60;
}
