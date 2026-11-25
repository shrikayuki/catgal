package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CATGAL_ES_TEST_URL", matches = ".+")
class CatalogSearchLiveTest {
    private final SearchProperties properties = properties();
    private final WebClient web = new SearchConfiguration().gameSearchWebClient(properties);

    private static SearchProperties properties() {
        SearchProperties properties = new SearchProperties();
        properties.setUrl(System.getenv("CATGAL_ES_TEST_URL"));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        properties.setAlias("catgal-game-test-" + suffix);
        properties.setResourceAlias("catgal-resource-test-" + suffix);
        return properties;
    }

    @Test
    void gameKeywordsMatchEachFieldAndRespectBoostsAndExplicitSort() {
        ElasticsearchGameSearch client = new ElasticsearchGameSearch(web, new ObjectMapper(), properties);
        String index = client.createIndex();
        try {
            Map<Long, Map<String, Object>> docs = new HashMap<>();
            docs.put(1L, game(1, "星空", "", List.of(), List.of()));
            docs.put(2L, game(2, "不同A", "星空", List.of(), List.of()));
            docs.put(3L, game(3, "不同B", "", List.of("星空"), List.of()));
            docs.put(4L, game(4, "不同C", "", List.of(), List.of("星空")));
            client.write(index, List.of(1L, 2L, 3L, 4L), docs);
            client.refresh(index);
            client.activate(index);
            GamePageQuery query = new GamePageQuery();
            query.setKeyword("星空");
            assertEquals(List.of(1L, 3L, 4L, 2L), client.search(query).ids());
            query.setName("不同");
            assertEquals(List.of(3L, 4L, 2L), client.search(query).ids());
            query.setName(null);
            query.setSortBy("rating");
            query.setIsAsc(true);
            assertEquals(List.of(1L, 2L, 3L, 4L), client.search(query).ids());
            query.setKeyword("不存在的关键字");
            assertEquals(0, client.search(query).total());
        } finally {
            remove(index);
        }
    }

    @Test
    void resourceSearchRetainsReviewStatesFiltersAndPersistentDeleteVersions() {
        ElasticsearchResourceSearch client = new ElasticsearchResourceSearch(web, new ObjectMapper(), properties);
        String index = client.createIndex();
        try {
            client.write(index, List.of(1L, 2L, 3L, 4L), Map.of(
                    1L, resource(1, 0, 1), 2L, resource(2, 1, 1), 3L, resource(3, 2, 1),
                    4L, Map.of("id", 4L, "version", 1L, "deleted", true)));
            client.refresh(index);
            client.activate(index);
            AdminPageQuery query = new AdminPageQuery();
            query.setKeyword("本体");
            assertEquals(List.of(3L, 2L, 1L), client.search(query).ids());
            query.setStatus(0);
            assertEquals(List.of(1L), client.search(query).ids());
            query.setStatus(2);
            query.setGameId(7L);
            query.setUserId(9L);
            assertEquals(List.of(3L), client.search(query).ids());
            query.setUserId(99L);
            assertEquals(0, client.search(query).total());
            query.setUserId(9L);
            client.write(properties.getResourceAlias(), List.of(3L), Map.of(3L, resource(3, 1, 3)));
            client.write(properties.getResourceAlias(), List.of(3L), Map.of(3L, resource(3, 2, 2)));
            client.refresh(index);
            assertEquals(0, client.search(query).total());
            client.write(properties.getResourceAlias(), List.of(3L), Map.of(3L,
                    Map.of("id", 3L, "version", 4L, "deleted", true)));
            client.refresh(index);
            query.setStatus(null);
            assertEquals(List.of(2L, 1L), client.search(query).ids());
        } finally {
            remove(index);
        }
    }

    private Map<String, Object> game(long id, String name, String introduction, List<String> tags, List<String> companies) {
        return Map.of("id", id, "version", 1L, "status", 1, "name", name, "introduction", introduction,
                "tag_names", tags, "company_names", companies, "rating", id);
    }

    @Test
    void upgradeKeepsLegacyIndexWritableAndEnablesKeywordsAfterAliasSwitch() {
        ElasticsearchGameSearch client = new ElasticsearchGameSearch(web, new ObjectMapper(), properties);
        String legacy = properties.getAlias() + "-legacy";
        web.put().uri("/" + legacy).header("Content-Type", "application/json").bodyValue(
                "{\"settings\":{\"number_of_replicas\":0},\"mappings\":{\"dynamic\":\"strict\",\"properties\":{"
                        + "\"id\":{\"type\":\"long\"},\"version\":{\"type\":\"long\"},"
                        + "\"status\":{\"type\":\"integer\"},\"name\":{\"type\":\"wildcard\"},"
                        + "\"rating\":{\"type\":\"double\"}}}}")
                .retrieve().toBodilessEntity().block(properties.getTimeout());
        String expanded = null;
        try {
            client.activate(legacy);
            Map<String, Object> document = game(1, "Another title", "特有介绍词", List.of("标签"), List.of("会社"));
            client.write(properties.getAlias(), List.of(1L), Map.of(1L, document));
            client.refresh(legacy);
            assertTrue(document.containsKey("introduction"));
            var stored = web.get().uri("/" + legacy + "/_doc/1").retrieve()
                    .bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block(properties.getTimeout());
            assertNotNull(stored);
            assertFalse(stored.path("_source").has("introduction"));
            GamePageQuery query = new GamePageQuery();
            query.setKeyword("特有介绍词");
            assertThrows(IllegalStateException.class, () -> client.search(query));
            expanded = client.createIndex();
            client.write(expanded, List.of(1L), Map.of(1L, document));
            client.refresh(expanded);
            client.activate(expanded);
            assertEquals(List.of(1L), client.search(query).ids());
        } finally {
            remove(legacy);
            if (expanded != null) remove(expanded);
        }
    }

    private Map<String, Object> resource(long id, int status, long version) {
        return Map.of("id", id, "version", version, "deleted", false, "name", "本体资源", "status", status,
                "game_id", 7L, "user_id", 9L);
    }

    private void remove(String index) {
        web.delete().uri("/" + index).retrieve().toBodilessEntity().block(properties.getTimeout());
    }
}
