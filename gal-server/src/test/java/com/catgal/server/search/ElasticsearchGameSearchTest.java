package com.catgal.server.search;

import com.catgal.common.domain.query.GamePageQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchGameSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SearchProperties properties = new SearchProperties();

    private ElasticsearchGameSearch client(String response) {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:9200")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json").body(response).build())).build();
        return new ElasticsearchGameSearch(webClient, mapper, properties);
    }

    @Test
    void preservesChineseJapaneseAndSingleCharacterSubstrings() {
        assertEquals("*白昼夢の青写真*", ElasticsearchGameSearch.likePattern("白昼夢の青写真"));
        assertEquals("*夏*", ElasticsearchGameSearch.likePattern("夏"));
        assertEquals("*Summer Pockets*", ElasticsearchGameSearch.likePattern("Summer Pockets"));
    }

    @Test
    void translatesSqlWildcardsAndEscapesEsMetacharacters() {
        assertEquals("*a*b?c*", ElasticsearchGameSearch.likePattern("a%b_c"));
        assertEquals("*a\\*b\\?c*", ElasticsearchGameSearch.likePattern("a*b?c"));
        assertEquals("*a%b_c*", ElasticsearchGameSearch.likePattern("a\\%b\\_c"));
        assertEquals("*a%", ElasticsearchGameSearch.likePattern("a\\"));
    }

    @Test
    void appliesEveryFilterBeforePaginationAndUsesStableSort() {
        GamePageQuery query = new GamePageQuery();
        query.setName("夏");
        query.setReleaseYear(2024);
        query.setResourceType("汉化");
        query.setLanguage("中文");
        query.setPlatform("win");
        query.setTagId(2);
        query.setCompanyId(3L);
        query.setPageNo(2);
        query.setPageSize(10);
        JsonNode body = mapper.valueToTree(client("{}").searchBody(query));
        assertEquals(8, body.path("query").path("bool").path("filter").size());
        assertEquals(10, body.path("from").asInt());
        assertTrue(body.path("track_total_hits").asBoolean());
        assertEquals("desc", body.path("sort").get(0).path("create_time").path("order").asText());
        assertEquals("desc", body.path("sort").get(1).path("id").asText());
        assertTrue(body.toString().contains("2025-01-01"));
    }

    @Test
    void readsOrderedIdsAndExactTotal() {
        var page = client("{\"hits\":{\"total\":{\"value\":2,\"relation\":\"eq\"},\"hits\":[{\"_id\":\"9\"},{\"_id\":\"2\"}]}}")
                .search(new GamePageQuery());
        assertEquals(List.of(9L, 2L), page.ids());
        assertEquals(2, page.total());
    }

    @Test
    void keywordSearchesFourFieldsWithRelevanceAndPreservesExplicitSorting() {
        GamePageQuery query = new GamePageQuery();
        query.setKeyword("Key");
        JsonNode body = mapper.valueToTree(client("{}").searchBody(query));
        assertEquals(4, body.at("/query/bool/should").size());
        assertEquals(1, body.at("/query/bool/minimum_should_match").asInt());
        assertEquals("desc", body.at("/sort/0/_score").asText());
        query.setSortBy("rating");
        query.setIsAsc(false);
        body = mapper.valueToTree(client("{}").searchBody(query));
        assertEquals("desc", body.at("/sort/0/rating/order").asText());
        query.setName("Summer");
        body = mapper.valueToTree(client("{}").searchBody(query));
        assertEquals(2, body.at("/query/bool/filter").size());
    }

    @Test
    void rejectsPartialTimedOutOrApproximateSearchResults() {
        assertThrows(IllegalStateException.class, () -> client("{\"timed_out\":true}").search(new GamePageQuery()));
        assertThrows(IllegalStateException.class, () -> client("{\"_shards\":{\"failed\":1}}").search(new GamePageQuery()));
        assertThrows(IllegalStateException.class, () -> client("{\"hits\":{\"total\":{\"value\":10000,\"relation\":\"gte\"}}}")
                .search(new GamePageQuery()));
    }

    @Test
    void rejectsPartialBulkFailuresAndRetriesWithoutDiscardingBatch() {
        assertThrows(IllegalStateException.class, () -> client("{\"items\":[{\"index\":{\"status\":429}}]}")
                .write("catgal-game", List.of(1L), Map.of(1L, Map.of("id", 1, "version", 3))));
        assertThrows(IllegalStateException.class, () -> client("{\"items\":[]}")
                .write("catgal-game", List.of(1L), Map.of(1L, Map.of("id", 1, "version", 3))));
    }

    @Test
    void acceptsVersionConflictWhenNewerDocumentAlreadyExists() {
        assertDoesNotThrow(() -> client("{\"items\":[{\"index\":{\"status\":409,\"error\":{\"type\":\"version_conflict_engine_exception\"}}}]}")
                .write("catgal-game", List.of(1L), Map.of(1L, Map.of("id", 1, "version", 3))));
    }

    @Test
    void neverAutoCreatesTheLiveAliasAsAnUnversionedIndex() {
        List<String> urls = new ArrayList<>();
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:9200")
                .exchangeFunction(request -> {
                    urls.add(request.url().toString());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                            .body("{\"items\":[{\"index\":{\"status\":201}}]}").build());
                }).build();
        new ElasticsearchGameSearch(webClient, mapper, properties).write("catgal-game", List.of(1L),
                Map.of(1L, Map.of("id", 1, "version", 3)));
        assertEquals("http://localhost:9200/_bulk?require_alias=true", urls.get(0));
    }

    @Test
    void mappingUsesPersistentVersionsAndSubstringField() throws Exception {
        try (var input = getClass().getResourceAsStream("/search/game-index.json")) {
            JsonNode mapping = mapper.readTree(input);
            assertEquals("wildcard", mapping.at("/mappings/properties/name/type").asText());
            assertEquals("long", mapping.at("/mappings/properties/version/type").asText());
            assertEquals(0, mapping.at("/settings/number_of_replicas").asInt());
        }
    }
}
