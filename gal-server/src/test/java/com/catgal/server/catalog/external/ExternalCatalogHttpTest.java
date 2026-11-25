package com.catgal.server.catalog.external;

import com.catgal.common.exception.BusinessException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCatalogHttpTest {
    @Test
    void credentialsAreOnlySentToBangumi() {
        AtomicReference<ClientRequest> seen = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            seen.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("{}").build());
        }).build();
        var http = new ExternalCatalogHttp(client, "test/catgal", "private-value");
        http.request(ExternalSource.BANGUMI, "100", null, 1);
        assertEquals("api.bgm.tv", seen.get().url().getHost());
        assertEquals("Bearer private-value", seen.get().headers().getFirst("Authorization"));
        assertEquals("test/catgal", seen.get().headers().getFirst("User-Agent"));
        http.request(ExternalSource.VNDB, "v17", null, 1);
        assertEquals("api.vndb.org", seen.get().url().getHost());
        assertNull(seen.get().headers().getFirst("Authorization"));
    }

    @Test
    void upstreamErrorsDoNotLeakResponseBodies() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                .header("Content-Type", MediaType.TEXT_PLAIN_VALUE).body("private upstream response").build())).build();
        var http = new ExternalCatalogHttp(client, "test/catgal", "secret");
        var error = assertThrows(BusinessException.class, () -> http.request(ExternalSource.VNDB, "v17", null, 1));
        assertEquals(502, error.getHttpStatus());
        assertFalse(error.getMessage().contains("private"));
        assertNull(error.getCause());
    }

    @Test
    void locallyBoundsRequestsPerProvider() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body("{}").build())).build();
        var http = new ExternalCatalogHttp(client, "test/catgal", "");
        for (int i = 0; i < 150; i++) http.request(ExternalSource.VNDB, "v17", null, 1);
        assertEquals(429, assertThrows(BusinessException.class, () -> http.request(ExternalSource.VNDB, "v17", null, 1)).getHttpStatus());
        assertNotNull(http.request(ExternalSource.BANGUMI, "100", null, 1));
    }
}
