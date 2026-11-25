package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

/** Run explicitly against a development cluster. Only the random test index is deleted. */
@EnabledIfEnvironmentVariable(named = "CATGAL_ES_TEST_URL", matches = ".+")
class ElasticsearchGameSearchLiveTest {
    @Test
    void validatesMappingQueriesVersionsAndUnpublishAgainstRealElasticsearch() {
        SearchProperties properties = new SearchProperties();
        properties.setUrl(System.getenv("CATGAL_ES_TEST_URL"));
        properties.setAlias("catgal-game-test-" + UUID.randomUUID().toString().replace("-", ""));
        WebClient webClient = new SearchConfiguration().gameSearchWebClient(properties);
        ElasticsearchGameSearch client = new ElasticsearchGameSearch(webClient, new ObjectMapper(), properties);
        String index = client.createIndex();
        try {
            client.write(index, List.of(1L, 2L, 3L), Map.of(
                    1L, document(1, "白昼夢の青写真", 1),
                    2L, document(2, "Summer Pockets", 1),
                    3L, document(3, "夏*a?b", 1)));
            client.refresh(index);
            client.activate(index);

            GamePageQuery query = new GamePageQuery();
            query.setName("青写");
            assertEquals(List.of(1L), client.search(query).ids());
            query.setName("summer");
            assertEquals(List.of(2L), client.search(query).ids());
            query.setName("夏");
            assertEquals(List.of(3L), client.search(query).ids());
            query.setName("*a?");
            assertEquals(List.of(3L), client.search(query).ids());
            query.setName("%P_ckets");
            assertEquals(List.of(2L), client.search(query).ids());

            query.setName("夏");
            query.setReleaseYear(2024);
            query.setTagId(2);
            query.setCompanyId(3L);
            query.setResourceType("汉化");
            query.setLanguage("中文");
            query.setPlatform("win");
            assertEquals(1, client.search(query).total());
            query.setTagId(999);
            assertEquals(0, client.search(query).total());

            client.write(properties.getAlias(), List.of(3L), Map.of(3L, document(3, "新名称", 5)));
            client.write(properties.getAlias(), List.of(3L), Map.of(3L, document(3, "过时名称", 2)));
            client.refresh(index);
            GamePageQuery renamed = new GamePageQuery();
            renamed.setName("新名称");
            assertEquals(List.of(3L), client.search(renamed).ids());

            client.write(properties.getAlias(), List.of(3L), Map.of(3L,
                    Map.of("id", 3L, "version", 6L, "status", 0)));
            client.refresh(index);
            assertEquals(0, client.search(renamed).total());

            GamePageQuery all = new GamePageQuery();
            all.setPageSize(1);
            assertEquals(List.of(2L), client.search(all).ids());
            all.setPageNo(2);
            assertEquals(List.of(1L), client.search(all).ids());
        } finally {
            webClient.delete().uri("/" + index).retrieve().toBodilessEntity().block(properties.getTimeout());
        }
    }

    private Map<String, Object> document(long id, String name, long version) {
        Map<String, Object> document = new HashMap<>();
        document.put("id", id);
        document.put("name", name);
        document.put("version", version);
        document.put("status", 1);
        document.put("create_time", "2024-01-01T00:00:00");
        document.put("release_date", "2024-06-01");
        document.put("types", List.of("汉化", "pc"));
        document.put("languages", List.of("中文"));
        document.put("platforms", List.of("win"));
        document.put("tag_ids", List.of(2));
        document.put("company_ids", List.of(3));
        return document;
    }
}
