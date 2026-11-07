package com.catgal.server.config.ossConfig;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "aliyun.oss")
@Data
public class AliOssProperties {

    private String endpoint;
    @ToString.Exclude
    private String accessKeyId;
    @ToString.Exclude
    private String accessKeySecret;
    private String bucketName;

}
