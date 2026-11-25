/*
 * 阅读说明
 * 封装固定资料来源的 HTTP 请求、超时、体积和调用频率约束。
 */
package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.EnumMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@Slf4j
public class ExternalCatalogHttp {
    private final WebClient client;
    private final String userAgent;
    private final String bangumiToken;
    private final Map<ExternalSource, ArrayDeque<Long>> recentRequests = new EnumMap<>(ExternalSource.class);

    public ExternalCatalogHttp(@Qualifier("externalCatalogWebClient") WebClient client,
            @Value("${catgal.catalog.external.user-agent:shrikayuki/catgal/0.1 (https://github.com/shrikayuki/catgal)}") String userAgent,
            @Value("${catgal.catalog.external.bangumi-token:}") String bangumiToken) {
        this.client = client;
        this.userAgent = userAgent;
        this.bangumiToken = bangumiToken;
    }

    // 固定来源地址与受控路径构成请求，限制响应体积、超时和失败传播。
    public JsonNode request(ExternalSource source, String id, String keyword, int pageNo) {
        reserveRequest(source);
        String uri;
        Map<String, Object> body = null;
        HttpMethod method = HttpMethod.POST;
        if (source == ExternalSource.BANGUMI) {
            uri = "https://api.bgm.tv/v0/";
            if (id != null) {
                uri += "subjects/" + source.normalizeId(id);
                method = HttpMethod.GET;
            } else {
                uri += "search/subjects?limit=10&offset=" + ((pageNo - 1) * 10);
                body = Map.of("keyword", keyword, "sort", "match", "filter", Map.of("type", List.of(4)));
            }
        } else {
            uri = "https://api.vndb.org/kana/vn";
            body = Map.of("filters", List.of(id == null ? "search" : "id", "=", id == null ? keyword : source.normalizeId(id)),
                    "fields", "title,alttitle,released,description,image.url,image.sexual,image.violence",
                    "results", id == null ? 10 : 1, "page", id == null ? pageNo : 1);
        }
        try {
            WebClient.RequestBodySpec request = client.method(method).uri(uri)
                    .header(HttpHeaders.USER_AGENT, userAgent).accept(MediaType.APPLICATION_JSON);
            if (source == ExternalSource.BANGUMI && !bangumiToken.isBlank()) request.headers(h -> h.setBearerAuth(bangumiToken));
            WebClient.RequestHeadersSpec<?> spec = body == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            JsonNode response = spec.retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
            if (response == null || !response.isObject()) throw unavailable();
            return response;
        } catch (WebClientResponseException exception) {
            log.warn("External catalog request failed, source={}, httpStatus={}", source.key(), exception.getStatusCode().value());
            if (exception.getStatusCode().value() == 404) throw BusinessException.notFound("外部条目不存在或不可访问");
            if (exception.getStatusCode().value() == 429) throw new BusinessException(429, 429, "来源站请求频繁，请稍后重试");
            throw unavailable();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Upstream response bodies and authorization headers must not reach logs or clients.
            log.warn("External catalog request failed, source={}, failureType={}", source.key(), exception.getClass().getSimpleName());
            throw unavailable();
        }
    }

    static BusinessException unavailable() { return new BusinessException(502, 502, "外部资料服务暂不可用，请稍后重试"); }

    // 外部调用前预留频率预算，避免管理页面重复点击瞬间冲击上游。
    private synchronized void reserveRequest(ExternalSource source) {
        long now = System.nanoTime();
        ArrayDeque<Long> requests = recentRequests.computeIfAbsent(source, ignored -> new ArrayDeque<>());
        while (!requests.isEmpty() && now - requests.peekFirst() >= Duration.ofMinutes(5).toNanos()) requests.removeFirst();
        if (requests.size() >= 150) throw new BusinessException(429, 429, "外部资料请求额度已用完，请稍后重试");
        requests.addLast(now);
    }

    static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    static String safeImage(String value) {
        if (value == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && host != null && (host.equals("lain.bgm.tv") || host.equals("t.vndb.org"))) return value;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
