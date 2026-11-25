/*
 * 阅读说明
 * 将资源审核筛选转换为 ES 查询，详情与状态由上层回库复核。
 */
package com.catgal.server.search;

import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class ElasticsearchResourceSearch implements ResourceSearchPort, SearchIndexWriter {
    private final ElasticsearchIndexOperations operations;
    private final SearchProperties properties;

    public ElasticsearchResourceSearch(@Qualifier("gameSearchWebClient") WebClient client,
                                       ObjectMapper mapper, SearchProperties properties) {
        this.operations = new ElasticsearchIndexOperations(client, mapper, properties);
        this.properties = properties;
        ElasticsearchIndexOperations.validateAlias(properties.getResourceAlias());
    }

    @Override
    public SearchPage search(AdminPageQuery query) {
        JsonNode response = operations.request(HttpMethod.POST, "/" + properties.getResourceAlias() + "/_search", searchBody(query));
        if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() > 0
                || !response.path("hits").path("total").path("relation").asText().equals("eq")) {
            throw new IllegalStateException("Incomplete resource search response");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            ids.add(Long.parseLong(hit.path("_id").asText()));
        }
        return new SearchPage(response.path("hits").path("total").path("value").asLong(), ids);
    }

    Map<String, Object> searchBody(AdminPageQuery query) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("deleted", false)));
        if (query.getStatus() != null) filters.add(Map.of("term", Map.of("status", query.getStatus())));
        if (query.getGameId() != null) filters.add(Map.of("term", Map.of("game_id", query.getGameId())));
        if (query.getUserId() != null) filters.add(Map.of("term", Map.of("user_id", query.getUserId())));
        if (StringUtils.isNotBlank(query.getKeyword())) {
            filters.add(Map.of("wildcard", Map.of("name", Map.of(
                    "value", SearchText.likePattern(query.getKeyword()), "case_insensitive", true))));
        }
        var page = query.toSafePage();
        return Map.of("from", (page.getCurrent() - 1) * page.getSize(), "size", page.getSize(),
                "track_total_hits", true, "_source", false,
                "query", Map.of("bool", Map.of("filter", filters)), "sort", List.of(Map.of("id", "desc")));
    }

    @Override
    public String createIndex() {
        return operations.createIndex(properties.getResourceAlias(), "search/resource-index.json", 1);
    }

    @Override
    public void write(String index, List<Long> ids, Map<Long, Map<String, Object>> documents) {
        operations.write(index, properties.getResourceAlias(), ids, documents);
    }

    @Override
    public void refresh(String index) { operations.refresh(index); }

    @Override
    public void activate(String index) { operations.activate(index, properties.getResourceAlias()); }
}
