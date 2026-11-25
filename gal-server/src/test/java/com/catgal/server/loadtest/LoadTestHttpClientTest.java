package com.catgal.server.loadtest;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadTestHttpClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private String base;
    private final LoadTestHttpClient client = new LoadTestHttpClient();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    private void route(String path, int status, String body) {
        server.createContext(path, exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    @Test void validatesBusinessAndNestedPageAndDropsErrorBodies() {
        route("/ok", 200, "{\"code\":200,\"data\":{\"total\":8,\"list\":[]}}");
        route("/nested", 200, "{\"code\":200,\"data\":{\"page\":{\"total\":0,\"list\":[]}}}");
        route("/bad", 200, "{\"code\":500,\"msg\":\"private-value\",\"data\":{}}");
        route("/http", 503, "{\"secret\":\"private-value\"}");
        assertTrue(client.request(base + "/ok", false).success());
        assertTrue(client.request(base + "/nested", true).success());
        assertEquals("BUSINESS_RESPONSE", client.request(base + "/nested", false).error());
        assertEquals("BUSINESS_RESPONSE", client.request(base + "/bad", false).error());
        assertEquals("HTTP_503", client.request(base + "/http", false).error());
    }

    @Test void doesNotFollowRedirectsAndBoundsResponseBody() {
        route("/large", 200, "{\"payload\":\"" + "x".repeat(600_000) + "\"}");
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", base + "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assertEquals("HTTP_302", client.request(base + "/redirect", false).error());
        assertEquals("RESPONSE_TOO_LARGE", client.request(base + "/large", false).error());
    }

    @Test void boundsSlowResponses() {
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(5000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        assertEquals("TIMEOUT", client.request(base + "/slow", false).error());
    }

    @Test void preservesEncodedChineseQueryWithoutDoubleEncoding() {
        server.createContext("/query", exchange -> {
            String decoded = exchange.getRequestURI().getQuery();
            String body = decoded.equals("keyword=测试")
                    ? "{\"code\":200,\"data\":{\"total\":0,\"list\":[]}}" : "{\"code\":400}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        assertTrue(client.request(base + "/query?keyword=%E6%B5%8B%E8%AF%95", false).success());
    }

    @Test void supportsLegacyEmptyAssociatedPagesWhileRejectingIncompleteBodies() {
        route("/empty", 200, "{\"code\":200,\"data\":{\"name\":\"Game\",\"page\":null}}");
        route("/missing", 200, "{\"code\":200,\"data\":{\"name\":\"Game\"}}");
        route("/broken", 200, "{\"code\":200,\"data\":{\"page\":null}}");
        assertTrue(client.request(base + "/empty", true).success());
        assertFalse(client.request(base + "/empty", false).success());
        assertFalse(client.request(base + "/missing", true).success());
        assertFalse(client.request(base + "/broken", true).success());
    }
}
