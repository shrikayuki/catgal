/*
 * 阅读说明
 * ES 索引创建、版本化文档批量写入、刷新与别名切换的具体适配。
 * 重建先写新索引，激活后使用别名向查询提供数据。
 */
package com.catgal.server.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Shared transport for versioned indexes, bulk validation and atomic alias switching. */
public class ElasticsearchIndexOperations {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final SearchProperties properties;
    private final Map<String, MappingVersion> mappingVersions = new ConcurrentHashMap<>();

    public ElasticsearchIndexOperations(WebClient client, ObjectMapper mapper, SearchProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    public static void validateAlias(String alias) {
        if (alias == null || !alias.matches("[a-z][a-z0-9-]{0,79}")) {
            throw new IllegalArgumentException("Invalid search alias");
        }
    }

    // 短时间缓存 mapping 版本，查询端据此决定新字段协议是否可用。
    public int schemaVersion(String alias) {
        MappingVersion cached = mappingVersions.get(alias);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.version();
        JsonNode mappings = request(HttpMethod.GET, "/" + alias + "/_mapping", null);
        int version = Integer.MAX_VALUE;
        for (JsonNode index : mappings) {
            version = Math.min(version, index.at("/mappings/_meta/schema_version").asInt(1));
        }
        if (version == Integer.MAX_VALUE) throw new IllegalStateException("Missing index mapping");
        mappingVersions.put(alias, new MappingVersion(version, Instant.now().plusSeconds(5)));
        return version;
    }

    public String createIndex(String alias, String mappingPath, int schemaVersion) {
        String index = alias + "-v" + schemaVersion + "-" + UUID.randomUUID().toString().replace("-", "");
        try {
            String mapping = new ClassPathResource(mappingPath)
                    .getContentAsString(StandardCharsets.UTF_8);
            request(HttpMethod.PUT, "/" + index, mapper.readTree(mapping));
            return index;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load index mapping", exception);
        }
    }

    // 以 external_gte 源版本提交 NDJSON bulk，逐条检查结果，已被更新版本覆盖的 409 可视为过期任务。
    public void write(String index, String alias, List<Long> ids, Map<Long, Map<String, Object>> documents) {
        if (ids.isEmpty()) {
            return;
        }
        StringBuilder bulk = new StringBuilder();
        try {
            for (Long id : ids) {
                Map<String, Object> document = documents.get(id);
                if (document == null || !document.containsKey("version")) {
                    throw new IllegalStateException("Missing versioned index document");
                }
                bulk.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", index,
                        "_id", id.toString(), "version", document.get("version"), "version_type", "external_gte")))).append('\n');
                bulk.append(mapper.writeValueAsString(document)).append('\n');
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot serialize index batch", exception);
        }
        String bulkPath = index.equals(alias) ? "/_bulk?require_alias=true" : "/_bulk";
        JsonNode response = client.post().uri(bulkPath)
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .bodyValue(bulk.toString()).retrieve().bodyToMono(JsonNode.class).block(properties.getTimeout());
        if (response == null || !response.has("items") || response.path("items").size() != ids.size()) {
            throw new IllegalStateException("Incomplete Elasticsearch bulk response");
        }
        for (JsonNode item : response.path("items")) {
            JsonNode operation = item.elements().next();
            int status = operation.path("status").asInt();
            boolean superseded = status == 409 && operation.path("error").path("type").asText()
                    .equals("version_conflict_engine_exception");
            if ((status < 200 || status >= 300) && !superseded) {
                throw new IllegalStateException("Elasticsearch bulk item failed with status " + status);
            }
        }
    }

    public void refresh(String index) {
        JsonNode response = request(HttpMethod.POST, "/" + index + "/_refresh", null);
        if (response.path("_shards").path("failed").asInt() > 0) {
            throw new IllegalStateException("Elasticsearch refresh failed on some shards");
        }
    }

    // 同一 alias 请求中移除旧别名并添加新索引，确认成功后清理 mapping 版本缓存。
    public void activate(String index, String alias) {
        List<Object> actions = new ArrayList<>();
        try {
            JsonNode existing = request(HttpMethod.GET, "/_alias/" + alias, null);
            existing.fieldNames().forEachRemaining(old -> actions.add(
                    Map.of("remove", Map.of("index", old, "alias", alias))));
        } catch (WebClientResponseException.NotFound ignored) {
            // First import has no existing alias.
        }
        actions.add(Map.of("add", Map.of("index", index, "alias", alias)));
        JsonNode response = request(HttpMethod.POST, "/_aliases", Map.of("actions", actions));
        if (!response.path("acknowledged").asBoolean()) {
            throw new IllegalStateException("Elasticsearch alias switch was not acknowledged");
        }
        mappingVersions.remove(alias);
    }

    public JsonNode request(HttpMethod method, String path, Object body) {
        WebClient.RequestBodySpec request = client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> headers = body == null ? request : request.bodyValue(body);
        JsonNode response = headers.retrieve().bodyToMono(JsonNode.class).block(properties.getTimeout());
        if (response == null) {
            throw new IllegalStateException("Empty Elasticsearch response");
        }
        return response;
    }

    private record MappingVersion(int version, Instant expiresAt) { }
}
