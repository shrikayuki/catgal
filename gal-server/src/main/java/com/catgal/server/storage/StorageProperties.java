/*
 * 阅读说明
 * 对象存储开关、endpoint、bucket 与凭据等参数绑定对象。
 */
package com.catgal.server.storage;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "catgal.storage")
public class StorageProperties {
    private boolean enabled;
    private String endpoint = "http://127.0.0.1:9000";
    @ToString.Exclude
    private String accessKey;
    @ToString.Exclude
    private String secretKey;
    private String bucket = "catgal-media";
}
