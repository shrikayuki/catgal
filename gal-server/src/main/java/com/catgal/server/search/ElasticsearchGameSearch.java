/*
 * 阅读说明
 * 将游戏筛选转换为 ES 查询，返回有序 ID 与总量，由上层回库组装。
 */
package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class ElasticsearchGameSearch implements GameSearchPort, GameIndexWriter {
    private final ElasticsearchIndexOperations operations;
    private final SearchProperties properties;

    public ElasticsearchGameSearch(@Qualifier("gameSearchWebClient") WebClient client,
                                   ObjectMapper mapper, SearchProperties properties) {
        this.operations = new ElasticsearchIndexOperations(client, mapper, properties);
        this.properties = properties;
        ElasticsearchIndexOperations.validateAlias(properties.getAlias());
    }

    @Override
    public SearchPage search(GamePageQuery query) {
        if (StringUtils.isNotBlank(query.getKeyword()) && operations.schemaVersion(properties.getAlias()) < 2) {
            throw new IllegalStateException("Game keyword search requires a v2 index rebuild");
        }
        JsonNode response = operations.request(HttpMethod.POST, "/" + properties.getAlias() + "/_search", searchBody(query));
        if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() > 0
                || !response.path("hits").path("total").path("relation").asText().equals("eq")) {
            throw new IllegalStateException("Incomplete Elasticsearch search response");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            ids.add(Long.parseLong(hit.path("_id").asText()));
        }
        return new SearchPage(response.path("hits").path("total").path("value").asLong(), ids);
    }

    Map<String, Object> searchBody(GamePageQuery query) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("status", 1)));
        if (StringUtils.isNotBlank(query.getName())) {
            // SQL LIKE's % and _ retain their wildcard meaning. ES-only * ? and backslash are escaped.
            filters.add(Map.of("wildcard", Map.of("name", Map.of(
                    "value", likePattern(query.getName()), "case_insensitive", true))));
        }
        if (query.getReleaseYear() != null) {
            int year = query.getReleaseYear();
            filters.add(Map.of("range", Map.of("release_date", Map.of(
                    "gte", Year.of(year).atDay(1).toString(),
                    "lt", Year.of(year).plusYears(1).atDay(1).toString()))));
        }
        addTerm(filters, "types", query.getResourceType());
        addTerm(filters, "languages", query.getLanguage());
        addTerm(filters, "platforms", query.getPlatform());
        addTerm(filters, "tag_ids", query.getTagId());
        addTerm(filters, "company_ids", query.getCompanyId());
        var order = query.toMpPage().orders().get(0);
        List<Object> sort = List.of(
                Map.of(order.getColumn(), Map.of("order", order.isAsc() ? "asc" : "desc",
                        "missing", order.isAsc() ? "_first" : "_last")),
                Map.of("id", "desc"));
        Map<String, Object> bool = new java.util.LinkedHashMap<>();
        bool.put("filter", filters);
        if (StringUtils.isNotBlank(query.getKeyword())) {
            List<Object> clauses = new ArrayList<>();
            Map<String, Integer> weights = Map.of("name", 10, "tag_names", 4, "company_names", 3, "introduction", 1);
            weights.forEach((field, weight) -> clauses.add(Map.of("wildcard", Map.of(field,
                    Map.of("value", likePattern(query.getKeyword()), "case_insensitive", true, "boost", weight)))));
            bool.put("should", clauses);
            bool.put("minimum_should_match", 1);
            if (StringUtils.isBlank(query.getSortBy()) || "relevance".equals(query.getSortBy())) {
                sort = List.of(Map.of("_score", "desc"), Map.of("id", "desc"));
            }
        }
        return Map.of("from", (query.getPageNo() - 1L) * query.getPageSize(),
                "size", query.getPageSize(), "track_total_hits", true, "_source", false,
                "query", Map.of("bool", bool), "sort", sort);
    }

    static String likePattern(String keyword) {
        return SearchText.likePattern(keyword);
    }

    private void addTerm(List<Object> filters, String field, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            filters.add(Map.of("term", Map.of(field, value)));
        }
    }

    @Override
    public String createIndex() {
        return operations.createIndex(properties.getAlias(), "search/game-index.json", 2);
    }

    @Override
    public void write(String index, List<Long> ids, Map<Long, Map<String, Object>> documents) {
        boolean expanded = documents.values().stream().anyMatch(document -> document.containsKey("introduction")
                || document.containsKey("tag_names") || document.containsKey("company_names"));
        if (index.equals(properties.getAlias()) && expanded && operations.schemaVersion(index) < 2) {
            Map<Long, Map<String, Object>> legacyDocuments = new java.util.HashMap<>();
            documents.forEach((id, document) -> {
                Map<String, Object> legacy = new java.util.LinkedHashMap<>(document);
                legacy.remove("introduction");
                legacy.remove("tag_names");
                legacy.remove("company_names");
                legacyDocuments.put(id, legacy);
            });
            documents = legacyDocuments;
        }
        operations.write(index, properties.getAlias(), ids, documents);
    }

    @Override
    public void refresh(String index) {
        operations.refresh(index);
    }

    @Override
    public void activate(String index) {
        operations.activate(index, properties.getAlias());
    }
}
