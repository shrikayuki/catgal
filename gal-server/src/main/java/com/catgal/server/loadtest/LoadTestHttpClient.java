/*
 * 阅读说明
 * 只向配置的本地固定目标发送只读测试请求，并校验 HTTP 与业务响应。
 */
package com.catgal.server.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.net.URI;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class LoadTestHttpClient {
    private final WebClient client;

    public LoadTestHttpClient() {
        client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .followRedirect(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                        .responseTimeout(Duration.ofSeconds(3))))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(512 * 1024)).build();
    }

    public record Outcome(boolean success, String error) { }

    public Outcome request(String uri, boolean nestedPage) {
        try {
            return client.get().uri(URI.create(uri)).exchangeToMono(response -> {
                if (response.statusCode().value() != 200) {
                    return response.releaseBody().thenReturn(new Outcome(false, "HTTP_" + response.statusCode().value()));
                }
                return response.bodyToMono(JsonNode.class).map(body -> {
                    JsonNode data = body.path("data");
                    JsonNode page = nestedPage ? data.path("page") : data;
                    // Existing associated-list APIs return an explicit null page for empty results.
                    boolean legacyEmpty = nestedPage && data.has("page") && page.isNull()
                            && data.path("name").isTextual() && !data.path("name").asText().isBlank();
                    boolean paged = page.path("total").isIntegralNumber() && page.path("total").asLong() >= 0
                            && page.path("list").isArray();
                    boolean valid = body.path("code").isIntegralNumber() && body.path("code").asInt() == 200
                            && (legacyEmpty || paged);
                    return new Outcome(valid, valid ? null : "BUSINESS_RESPONSE");
                }).defaultIfEmpty(new Outcome(false, "EMPTY_RESPONSE"));
            }).timeout(Duration.ofSeconds(4)).onErrorResume(error ->
                    Mono.just(new Outcome(false, classify(error)))).block();
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) return new Outcome(false, "CANCELLED");
            return new Outcome(false, "REQUEST_UNAVAILABLE");
        }
    }

    private static String classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof io.netty.handler.timeout.ReadTimeoutException
                    || cause instanceof io.netty.channel.ConnectTimeoutException) return "TIMEOUT";
            if (cause instanceof org.springframework.core.io.buffer.DataBufferLimitException) return "RESPONSE_TOO_LARGE";
            if (cause instanceof org.springframework.core.codec.CodecException) return "INVALID_RESPONSE";
        }
        return "REQUEST_UNAVAILABLE";
    }
}
