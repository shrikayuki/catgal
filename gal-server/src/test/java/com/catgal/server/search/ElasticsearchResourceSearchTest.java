package com.catgal.server.search;

import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchResourceSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ElasticsearchResourceSearch client(String response) {
        return new ElasticsearchResourceSearch(WebClient.builder().baseUrl("http://localhost:9200")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json").body(response).build())).build(), mapper, new SearchProperties());
    }

    @Test
    void statusGameAndPublisherFiltersAreAppliedBeforePaging() {
        AdminPageQuery query = new AdminPageQuery();
        query.setKeyword("本体");
        query.setStatus(2);
        query.setGameId(3L);
        query.setUserId(7L);
        query.setPageNo(3);
        query.setPageSize(10);
        JsonNode body = mapper.valueToTree(client("{}").searchBody(query));
        assertEquals(5, body.at("/query/bool/filter").size());
        assertEquals(20, body.path("from").asInt());
        assertEquals("desc", body.at("/sort/0/id").asText());
        assertTrue(body.path("track_total_hits").asBoolean());
        assertFalse(body.path("_source").asBoolean());
    }

    @Test
    void exactHitIdsAreReturnedAndPartialResultsRejected() {
        var page = client("{\"hits\":{\"total\":{\"value\":2,\"relation\":\"eq\"},\"hits\":[{\"_id\":\"9\"},{\"_id\":\"3\"}]}}")
                .search(new AdminPageQuery());
        assertEquals(List.of(9L, 3L), page.ids());
        assertThrows(IllegalStateException.class, () -> client("{\"timed_out\":true}").search(new AdminPageQuery()));
    }

    @Test
    void indexContainsOnlySearchableMetadata() throws Exception {
        try (var input = getClass().getResourceAsStream("/search/resource-index.json")) {
            JsonNode properties = mapper.readTree(input).at("/mappings/properties");
            assertEquals(7, properties.size());
            assertFalse(properties.has("download_url"));
            assertFalse(properties.has("extract_code"));
            assertFalse(properties.has("password"));
            assertFalse(properties.has("remark"));
        }
    }
}
