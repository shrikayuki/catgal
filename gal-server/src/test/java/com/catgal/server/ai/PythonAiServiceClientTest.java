package com.catgal.server.ai;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PythonAiServiceClientTest {
    @Test
    void memoryVectorRequiresLongTermMemoryBeforeConsentCanEnable() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> Mono.just(ClientResponse.create(HttpStatus.CONFLICT)
                .header("Content-Type", "application/json")
                .body("{\"code\":\"MEMORY_VECTOR_REQUIRES_MEMORY\",\"message\":\"private implementation details\"}").build()));
        BusinessException error = assertThrows(BusinessException.class, () -> client.persistenceRequest(
                "memory-vector/settings/update", Map.of("userId", 7L, "botId", 12L, "revision", 0L, "enabled", true)));
        assertEquals(409, error.getHttpStatus());
        assertEquals("请先启用长期记忆", error.getMessage());
    }

    @Test
    void memoryVectorSettingsUseFixedSignedOperationsAndSafeErrorMessages() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> {
            assertTrue(List.of("/internal/v1/memory-vector/settings/get", "/internal/v1/memory-vector/settings/update")
                    .contains(request.url().getPath()));
            assertNotNull(request.headers().getFirst(PythonAiServiceClient.SIGNATURE_HEADER));
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Content-Type", "application/json")
                    .body("{\"code\":\"MEMORY_VECTOR_UNAVAILABLE\",\"message\":\"private memory or embedding upstream data\"}").build());
        });
        for (String operation : List.of("memory-vector/settings/get", "memory-vector/settings/update")) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> client.persistenceRequest(operation, Map.of("userId", 7L, "botId", 12L)));
            assertEquals(503, error.getHttpStatus());
            assertEquals("记忆向量检索功能暂不可用", error.getMessage());
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> client.persistenceRequest("memory-vector/export", Map.of("userId", 7L, "botId", 12L))).getHttpStatus());
    }

    @Test
    void recommendationEventUsesSafeLocalGameReferences() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> {
            String id = request.headers().getFirst("X-Request-Id");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(wire("meta", "{\"requestId\":\"" + id + "\",\"sessionId\":\"s1\","
                            + "\"messageId\":\"2\",\"userMessageId\":\"1\",\"replayed\":false}")
                            + wire("delta", "{\"content\":\"QA recommendation\"}")
                            + wire("recommendations", "{\"requestId\":\"" + id
                                + "\",\"items\":[{\"id\":\"42\",\"name\":\"QA game\",\"url\":\"https://untrusted.example\"}]}")
                            + wire("done", "{\"requestId\":\"" + id + "\"}")).build());
        });
        List<AiChatEvent> events = client.persistentChat(Map.of("userId", 7L, "sessionId", "s1",
                "clientMessageId", "key-1", "content", "hello")).collectList().block();
        assertEquals("recommendations", events.get(2).type());
        assertEquals("42", events.get(2).items().get(0).id());
        assertFalse(events.toString().contains("untrusted.example"));
        assertEquals("done", events.get(3).type());
    }

    @Test
    void invalidRecommendationIdentityBecomesProtocolError() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> {
            String id = request.headers().getFirst("X-Request-Id");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(wire("meta", "{\"requestId\":\"" + id + "\",\"sessionId\":\"s1\","
                            + "\"messageId\":\"2\",\"userMessageId\":\"1\",\"replayed\":false}")
                            + wire("delta", "{\"content\":\"QA\"}")
                            + wire("recommendations", "{\"requestId\":\"" + id
                                + "\",\"items\":[{\"id\":\"../admin\",\"name\":\"QA\"}]}")).build());
        });
        List<AiChatEvent> events = client.persistentChat(Map.of("userId", 7L, "sessionId", "s1",
                "clientMessageId", "key-1", "content", "hello")).collectList().block();
        assertEquals("AI_PROTOCOL_ERROR", events.get(events.size() - 1).code());
        assertTrue(events.stream().noneMatch(event -> "done".equals(event.type())));
    }
    @Test
    void persistenceRpcSignsTheExactOperationAndMapsErrorsSafely() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> {
            assertEquals("/internal/v1/sessions/get", request.url().getPath());
            assertNotNull(request.headers().getFirst(PythonAiServiceClient.SIGNATURE_HEADER));
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", "application/json")
                    .body("{\"code\":\"SESSION_NOT_FOUND\",\"message\":\"private database error\"}").build());
        });
        BusinessException error = assertThrows(BusinessException.class,
                () -> client.persistenceRequest("sessions/get", Map.of("userId", 7L, "sessionId", "s1")));
        assertEquals(404, error.getHttpStatus());
        assertFalse(error.getMessage().contains("private"));
    }

    @Test
    void persistentStreamForwardsDatabaseMetadataAndSafeConflictCodes() {
        AiServiceProperties properties = configured();
        properties.setPersistenceEnabled(true);
        PythonAiServiceClient client = client(properties, request -> {
            String id = request.headers().getFirst("X-Request-Id");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(wire("meta", "{\"requestId\":\"" + id + "\",\"sessionId\":\"s1\","
                            + "\"messageId\":\"2\",\"userMessageId\":\"1\",\"replayed\":true,\"status\":\"completed\"}")
                            + wire("delta", "{\"content\":\"saved\"}")
                            + wire("done", "{\"requestId\":\"" + id + "\"}")).build());
        });
        List<AiChatEvent> result = client.persistentChat(Map.of("userId", 7L, "sessionId", "s1",
                "clientMessageId", "key-1", "content", "hello")).collectList().block();
        assertEquals("2", result.get(0).messageId());
        assertTrue(result.get(0).replayed());
        assertEquals("done", result.get(2).type());
    }

    private static final AiChatCommand COMMAND = new AiChatCommand(7L, "emily", "session-1", "你好", "系统");

    private PythonAiServiceClient client(AiServiceProperties properties,
                                         Function<ClientRequest, Mono<ClientResponse>> exchange) {
        return new PythonAiServiceClient(WebClient.builder().exchangeFunction(exchange::apply).build(),
                new ObjectMapper(), properties, new AiServiceRequestSigner());
    }

    private PythonAiServiceClient decodingClient() {
        return client(new AiServiceProperties(), request -> Mono.error(new AssertionError("No HTTP call expected")));
    }

    private AiServiceProperties configured() {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setHmacSecret("test-secret-that-is-long-enough-for-hmac");
        return properties;
    }

    private ServerSentEvent<String> event(String type, String data) {
        return ServerSentEvent.builder(data).event(type).build();
    }

    private String meta(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"sessionId\":\"session-1\"}";
    }

    private String wire(String event, String data) { return "event: " + event + "\ndata: " + data + "\n\n"; }

    @Test
    void decodesDeltaAndRequiresCorrelatedCompletion() {
        List<AiChatEvent> result = decodingClient().decodeStream("req-1", "session-1", Flux.just(
                event("meta", meta("req-1")), event("delta", "{\"content\":\"你\"}"),
                event("done", "{\"requestId\":\"req-1\"}")
        )).collectList().block();
        assertEquals(List.of(AiChatEvent.delta("req-1", "你"), AiChatEvent.done("req-1")), result);
    }

    @Test
    void prematureEofIsNotSuccessfulCompletion() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> decodingClient()
                .decodeStream("req-1", "session-1", Flux.just(event("meta", meta("req-1")),
                        event("delta", "{\"content\":\"partial\"}"))).blockLast());
        assertEquals("AI_STREAM_INTERRUPTED", exception.getMessage());
    }

    @Test
    void rejectsInvalidOrderingMalformedJsonAndMismatchedIdentity() {
        for (Flux<ServerSentEvent<String>> events : List.of(
                Flux.just(event("delta", "{\"content\":\"text\"}")),
                Flux.just(event("meta", meta("another-request"))),
                Flux.just(event("meta", "not-json")),
                Flux.just(event("meta", meta("req-1")), event("meta", meta("req-1"))),
                Flux.just(event("meta", meta("req-1")), event("delta", "{\"content\":23}")),
                Flux.just(event("meta", meta("req-1")), event("done", "{\"requestId\":\"req-1\"}"))
        )) {
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> decodingClient().decodeStream("req-1", "session-1", events).blockLast());
            assertEquals("AI_PROTOCOL_ERROR", exception.getMessage());
        }
    }

    @Test
    void convertsPythonErrorToSafeStructuredErrorAndLegacyServiceUnavailable() {
        PythonAiServiceClient client = client(configured(), request -> {
            String id = request.headers().getFirst("X-Request-Id");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(wire("error", "{\"requestId\":\"" + id
                            + "\",\"code\":\"MODEL_UPSTREAM_ERROR\",\"message\":\"provider-secret\"}"))
                    .build());
        });
        List<AiChatEvent> events = client.streamChatEvents(COMMAND).collectList().block();
        assertEquals(List.of("meta", "error"), events.stream().map(AiChatEvent::type).toList());
        assertEquals("MODEL_UPSTREAM_ERROR", events.get(1).code());
        assertFalse(events.toString().contains("provider-secret"));
        BusinessException error = assertThrows(BusinessException.class, () -> client.streamChat(COMMAND).blockLast());
        assertEquals(503, error.getHttpStatus());
    }

    @Test
    void missingSharedSecretDoesNotCallPython() {
        PythonAiServiceClient client = decodingClient();
        BusinessException error = assertThrows(BusinessException.class, () -> client.streamChat(COMMAND).blockLast());
        assertEquals(503, error.getHttpStatus());
    }

    @Test
    void rejectsNonSseResponseWithoutLeakingItsBody() {
        PythonAiServiceClient client = client(configured(), request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "text/html").body("private-proxy-diagnostic").build()));
        List<AiChatEvent> events = client.streamChatEvents(COMMAND).collectList().block();
        assertEquals("AI_PROTOCOL_ERROR", events.get(1).code());
        assertFalse(events.toString().contains("private-proxy-diagnostic"));
    }

    @Test
    void closingConnectionAfterPartialContentEmitsErrorInsteadOfDone() {
        PythonAiServiceClient client = client(configured(), request -> {
            String id = request.headers().getFirst("X-Request-Id");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(wire("meta", meta(id)) + wire("delta", "{\"content\":\"partial\"}")).build());
        });
        List<AiChatEvent> events = client.streamChatEvents(COMMAND).collectList().block();
        assertEquals(List.of("meta", "delta", "error"), events.stream().map(AiChatEvent::type).toList());
        assertEquals("AI_STREAM_INTERRUPTED", events.get(2).code());
    }

    @Test
    void cancellingDownstreamCancelsUpstreamSubscription() {
        AtomicBoolean cancelled = new AtomicBoolean();
        PythonAiServiceClient client = client(configured(), request -> {
            String id = request.headers().getFirst("X-Request-Id");
            Flux<org.springframework.core.io.buffer.DataBuffer> body = Flux.<org.springframework.core.io.buffer.DataBuffer>concat(
                    Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(
                            (wire("meta", meta(id)) + wire("delta", "{\"content\":\"partial\"}"))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))), Flux.never()
            ).doOnCancel(() -> cancelled.set(true));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(body).build());
        });
        client.streamChatEvents(COMMAND).filter(event -> "delta".equals(event.type())).next().block();
        assertTrue(cancelled.get());
    }

    @Test
    void overallDeadlineExpiresEvenWhileDeltasKeepArriving() throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean();
        var cancellation = new java.util.concurrent.CountDownLatch(1);
        AiServiceProperties properties = configured();
        properties.setStreamTimeout(Duration.ofMillis(300));
        properties.setReadTimeout(Duration.ofSeconds(2));
        PythonAiServiceClient client = client(properties, request -> {
            String id = request.headers().getFirst("X-Request-Id");
            var buffers = new org.springframework.core.io.buffer.DefaultDataBufferFactory();
            Flux<org.springframework.core.io.buffer.DataBuffer> body = Flux.concat(
                    Flux.just(wire("meta", meta(id))),
                    Flux.interval(Duration.ofMillis(10)).map(index -> wire("delta", "{\"content\":\"x\"}"))
            ).map(text -> (org.springframework.core.io.buffer.DataBuffer) buffers.wrap(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).doOnCancel(() -> {
                        cancelled.set(true);
                        cancellation.countDown();
                    });
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "text/event-stream")
                    .body(body).build());
        });
        List<AiChatEvent> events = client.streamChatEvents(COMMAND).collectList().block(Duration.ofSeconds(5));
        assertTrue(events.stream().anyMatch(event -> "delta".equals(event.type())));
        assertEquals("GENERATION_TIMEOUT", events.get(events.size() - 1).code());
        assertFalse(events.stream().anyMatch(event -> "done".equals(event.type())));
        assertTrue(cancellation.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(cancelled.get());
    }
}
