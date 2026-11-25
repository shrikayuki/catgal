/*
 * 阅读说明
 * ES WebClient 配置，统一地址、认证与网络请求边界。
 */
package com.catgal.server.search;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {
    @Bean("gameSearchWebClient")
    public WebClient gameSearchWebClient(SearchProperties properties) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(properties.getUrl())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                Math.toIntExact(properties.getTimeout().toMillis()))
                        .responseTimeout(properties.getTimeout())))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024));
        if (!properties.getUsername().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBasicAuth(
                    properties.getUsername(), properties.getPassword()));
        }
        return builder.build();
    }
}
