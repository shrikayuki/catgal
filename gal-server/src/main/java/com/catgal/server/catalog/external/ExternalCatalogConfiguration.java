/*
 * 阅读说明
 * 创建访问外部资料站点的 HTTP 客户端及请求边界。
 */
package com.catgal.server.catalog.external;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.time.Duration;

@Configuration
public class ExternalCatalogConfiguration {
    @Bean("externalCatalogWebClient")
    public WebClient externalCatalogWebClient(@Value("${catgal.catalog.external.proxy-url:}") String proxyUrl) {
        HttpClient transport = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(8)).followRedirect(false);
        if (!proxyUrl.isBlank()) {
            URI proxy;
            try {
                proxy = URI.create(proxyUrl);
                if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("External catalog proxy must be an HTTP proxy URL");
            }
            transport = transport.proxy(spec -> {
                var address = spec.type(ProxyProvider.Proxy.HTTP).host(proxy.getHost()).port(proxy.getPort() < 0 ? 80 : proxy.getPort());
                if (proxy.getUserInfo() != null) {
                    String[] credentials = proxy.getUserInfo().split(":", 2);
                    address.username(credentials[0]).password(ignored -> credentials.length > 1 ? credentials[1] : "");
                }
            });
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }
}
