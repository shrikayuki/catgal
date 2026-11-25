package com.catgal.server.ai;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiServiceResponseLimitTest {
    @Test void acceptsSupportedLargeMemoryPagesAndRejectsResponsesBeyondOneMegabyte() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode page = mapper.createObjectNode();
        ArrayNode items = page.putArray("items");
        for (int index = 1; index <= 100; index++) {
            items.addObject().put("id", String.valueOf(index)).put("botId", "12")
                    .put("content", "界".repeat(1000)).put("memoryType", "fact")
                    .put("revision", "1").putNull("sourceMessageId").putNull("sourceSessionId");
        }
        page.put("hasMore", false).putNull("nextCursor").putNull("lastExtraction");
        byte[] supportedPage = mapper.writeValueAsBytes(page);
        assertTrue(supportedPage.length > 256 * 1024);
        assertTrue(supportedPage.length < 1024 * 1024);
        AtomicReference<byte[]> responseBody = new AtomicReference<>(supportedPage);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/", exchange -> {
            requests.incrementAndGet();
            byte[] response = responseBody.get();
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (IOException exception) {
                // The client intentionally cancels the oversized response after reaching its bound.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            AiServiceProperties properties = new AiServiceProperties();
            properties.setPersistenceEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setHmacSecret("test-secret-that-is-long-enough-for-hmac");
            PythonAiServiceClient client = new PythonAiServiceClient(
                    new AiServiceClientConfig().aiServiceWebClient(properties), mapper,
                    properties, new AiServiceRequestSigner());
            Map<String, Object> payload = Map.of("userId", 7L, "botId", 12L, "limit", 100);
            for (String operation : new String[]{"memory/list", "memory-candidates/list"}) {
                var result = client.persistenceRequest(operation, payload);
                assertEquals(100, result.path("items").size());
                String content = result.path("items").get(0).path("content").asText();
                assertEquals(1000, content.codePointCount(0, content.length()));
            }
            byte[] oversizedPage = mapper.writeValueAsBytes(
                    mapper.createObjectNode().put("padding", "x".repeat(1024 * 1024)));
            assertTrue(oversizedPage.length > 1024 * 1024);
            responseBody.set(oversizedPage);
            BusinessException error = assertThrows(BusinessException.class,
                    () -> client.persistenceRequest("memory-candidates/list", payload));
            assertEquals(503, error.getHttpStatus());
            assertFalse(error.getMessage().contains("padding"));
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
