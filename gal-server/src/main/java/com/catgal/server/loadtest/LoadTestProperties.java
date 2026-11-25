/*
 * 阅读说明
 * 管理并发测试的开关与运行参数，目标地址和上限由服务端约束。
 */
package com.catgal.server.loadtest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "catgal.load-test")
public class LoadTestProperties {
    private boolean enabled;
    private int targetPort = 8080;
    private String targetPrefix = "";
}
