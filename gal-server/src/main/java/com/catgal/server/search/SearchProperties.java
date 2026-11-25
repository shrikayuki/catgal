/*
 * 阅读说明
 * ES 查询开关、索引名、批次、超时和失败冷却等搜索配置。
 */
package com.catgal.server.search;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("catgal.search")
public class SearchProperties {
    private boolean enabled;
    private boolean resourceEnabled;
    private String resourceAlias = "catgal-resource";
    private String url = "http://127.0.0.1:9200";
    private String username = "";
    private String password = "";
    private String alias = "catgal-game";
    private Duration timeout = Duration.ofSeconds(3);
    private Duration failureCooldown = Duration.ofSeconds(30);
    private int batchSize = 200;
}
