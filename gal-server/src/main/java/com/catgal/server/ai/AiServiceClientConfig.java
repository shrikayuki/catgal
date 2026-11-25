/*
 * 阅读说明
 * 创建 Java 到 Python 的 WebClient，统一设置地址、连接超时、响应超时和解码大小上限。
 */
package com.catgal.server.ai;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceClientConfig {

    @Bean
    @Qualifier("aiServiceWebClient")
    public WebClient aiServiceWebClient(AiServiceProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getReadTimeout());
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }
}
