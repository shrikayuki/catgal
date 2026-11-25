/*
 * 阅读说明
 * Java 到 Python 的 HTTP/SSE 适配器，统一签名、协议解析、超时和错误文案。
 * 持久化操作只接受服务器声明的操作集合，用户身份由 Java 认证上下文传递。
 */
package com.catgal.server.ai;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.catgal.common.constants.ErrorInfo.Code.SERVER_ERROR;

@Component
@Slf4j
public class PythonAiServiceClient implements AiServiceClient {
    static final String CHAT_PATH = "/internal/v1/chat/stream";
    static final String TIMESTAMP_HEADER = "X-Catgal-Timestamp";
    static final String NONCE_HEADER = "X-Catgal-Nonce";
    static final String SIGNATURE_HEADER = "X-Catgal-Signature";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final Map<String, String> ERROR_MESSAGES = Map.ofEntries(
            Map.entry("AI_SERVICE_UNAVAILABLE", "AI服务暂不可用"),
            Map.entry("AI_STREAM_INTERRUPTED", "AI响应提前中断，请读取消息状态后重试"),
            Map.entry("AI_PROTOCOL_ERROR", "AI服务响应格式异常"),
            Map.entry("GENERATION_TIMEOUT", "AI生成超时"),
            Map.entry("MODEL_UPSTREAM_ERROR", "AI模型响应失败"),
            Map.entry("SERVICE_BUSY", "AI服务繁忙，请稍后再试"),
            Map.entry("CONVERSATION_BUSY", "当前会话正在生成，请等待完成"),
            Map.entry("CONVERSATION_LEASE_LOST", "会话生成状态已失效，请核对结果"),
            Map.entry("MODEL_CIRCUIT_OPEN", "AI模型暂时不可用，请稍后再试"),
            Map.entry("MEMORY_UNAVAILABLE", "AI上下文存储暂不可用"),
            Map.entry("PERSISTENCE_UNAVAILABLE", "AI消息存储暂不可用"),
            Map.entry("SESSION_NOT_FOUND", "会话不存在"),
            Map.entry("CHARACTER_UNAVAILABLE", "AI角色不存在或未启用"),
            Map.entry("REQUEST_IN_PROGRESS", "此消息正在生成，请读取历史状态"),
            Map.entry("IDEMPOTENCY_CONFLICT", "消息幂等键与原请求不一致"),
            Map.entry("REGENERATION_CONFLICT", "只允许重新生成最近一轮回答"),
            Map.entry("MESSAGE_NOT_FOUND", "回答不存在"),
            Map.entry("GENERATION_INTERRUPTED", "此生成已中断，请读取消息状态"),
            Map.entry("SESSION_DELETED", "会话已删除"),
            Map.entry("MESSAGE_TOO_LONG", "消息过长"),
            Map.entry("PERSONALIZATION_UNAVAILABLE", "个人机器人功能未启用"),
            Map.entry("BOT_NOT_FOUND", "机器人不存在"),
            Map.entry("BOT_CONFIGURATION_ERROR", "机器人配置不可用"),
            Map.entry("CREDENTIAL_REQUIRED", "个人机器人需要绑定自己的模型凭据"),
            Map.entry("CREDENTIAL_UNAVAILABLE", "模型凭据不可用，请更新或切换凭据"),
            Map.entry("CREDENTIAL_NOT_FOUND", "模型凭据不存在"),
            Map.entry("PROVIDER_UNAVAILABLE", "模型来源未启用"),
            Map.entry("REVISION_CONFLICT", "配置已更新，请重新读取后提交"),
            Map.entry("INVALID_BOT_ASSET", "形象地址须来自已配置的图片来源"),
            Map.entry("PLATFORM_BOT_PROTECTED", "平台机器人可通过新建个人机器人进行定制"),
            Map.entry("GAME_TOOLS_UNAVAILABLE", "游戏查询服务暂不可用"),
            Map.entry("TOOL_BUDGET_EXCEEDED", "本次查询次数已达上限，请缩小查询范围"),
            Map.entry("LONG_TERM_MEMORY_UNAVAILABLE", "长期记忆功能暂不可用"),
            Map.entry("MEMORY_NOT_FOUND", "记忆不存在"),
            Map.entry("INVALID_MEMORY_SOURCE", "记忆来源消息不可用"),
            Map.entry("MEMORY_LIMIT_EXCEEDED", "记忆数量已达上限，请先整理已有记忆"),
            Map.entry("SUMMARY_UNAVAILABLE", "会话摘要功能暂不可用"),
            Map.entry("SOURCE_NOT_AVAILABLE", "来源会话或消息已失效"),
            Map.entry("MEMORY_CANDIDATES_UNAVAILABLE", "记忆候选功能暂不可用"),
            Map.entry("CANDIDATE_NOT_FOUND", "记忆候选不存在"),
            Map.entry("MEMORY_VECTOR_UNAVAILABLE", "记忆向量检索功能暂不可用"),
            Map.entry("MEMORY_VECTOR_REQUIRES_MEMORY", "请先启用长期记忆")
    );
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AiServiceProperties properties;
    private final AiServiceRequestSigner signer;

    public PythonAiServiceClient(@Qualifier("aiServiceWebClient") WebClient webClient,
                                 ObjectMapper objectMapper, AiServiceProperties properties,
                                 AiServiceRequestSigner signer) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signer = signer;
    }

    @Override
    public Flux<String> streamChat(AiChatCommand command) {
        return streamChatEvents(command).handle((event, sink) -> {
            if ("delta".equals(event.type())) {
                sink.next(event.content());
            } else if ("error".equals(event.type())) {
                sink.error(new BusinessException(SERVER_ERROR, 503, event.message()));
            }
        });
    }

    @Override
    public Flux<AiChatEvent> streamChatEvents(AiChatCommand command) {
        return Flux.defer(() -> {
            String requestId = UUID.randomUUID().toString();
            return Flux.concat(
                    Flux.just(AiChatEvent.meta(requestId, command.sessionId())),
                    Flux.defer(() -> executeStream(requestId, command))
            ).onErrorResume(error -> {
                String code = error instanceof StreamFailure failure ? failure.code
                        : error instanceof TimeoutException ? "GENERATION_TIMEOUT"
                        : "AI_SERVICE_UNAVAILABLE";
                log.warn("AI流式请求失败: requestId={}, code={}", requestId, code);
                return Flux.just(AiChatEvent.error(requestId, code, ERROR_MESSAGES.get(code)));
            });
        });
    }

    private Flux<AiChatEvent> executeStream(String requestId, AiChatCommand command) {
        String secret = properties.getHmacSecret();
        if (secret == null || secret.length() < 32) {
            return Flux.error(new StreamFailure("AI_SERVICE_UNAVAILABLE"));
        }
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new AiChatRequest(requestId, command.userId(),
                    command.sessionId(), command.girlCode(), command.userMessage(), command.systemPrompt()));
        } catch (JsonProcessingException exception) {
            return Flux.error(new StreamFailure("AI_PROTOCOL_ERROR"));
        }
        return executeStream(requestId, command.sessionId(), CHAT_PATH, body, false);
    }

    // 每次调用生成时间戳和 nonce，按实际请求路径与已序列化字节计算 HMAC。
    private WebClient.RequestHeadersSpec<?> signedRequest(String path, byte[] body, String requestId) {
        String secret = properties.getHmacSecret();
        if (secret == null || secret.length() < 32) throw new StreamFailure("AI_SERVICE_UNAVAILABLE");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = signer.sign(secret, timestamp, nonce, "POST", path, body);
        return webClient.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header(TIMESTAMP_HEADER, timestamp).header(NONCE_HEADER, nonce)
                .header(SIGNATURE_HEADER, signature).header("X-Request-Id", requestId).bodyValue(body);
    }

    @Override
    // 操作名必须属于服务端白名单，认证用户 ID 已由 Controller 补入请求。
    public JsonNode persistenceRequest(String operation, Map<String, Object> payload) {
        requirePersistence();
        // The operation is supplied only by server code, never by a request parameter.
        if (!java.util.Set.of("sessions/create", "sessions/list", "sessions/get",
                "sessions/delete", "messages/list", "templates/list", "providers/list",
                "bots/platform", "bots/create", "bots/list", "bots/get", "bots/update",
                "bots/delete", "bots/prompts", "credentials/create", "credentials/list",
                "credentials/rotate", "credentials/revoke", "memory/settings/get", "memory/settings/update",
                "memory/list", "memory/create", "memory/update", "memory/delete",
                "summary/get", "summary/settings", "summary/clear",
                "memory-candidates/settings/get", "memory-candidates/settings/update",
                "memory-candidates/list", "memory-candidates/confirm", "memory-candidates/reject",
                "memory-vector/settings/get", "memory-vector/settings/update").contains(operation)) {
            throw BusinessException.badRequest("AI操作无效");
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            JsonNode result = signedRequest("/internal/v1/" + operation, body, UUID.randomUUID().toString())
                    .accept(MediaType.APPLICATION_JSON).exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(JsonNode.class);
                        int status = response.statusCode().value();
                        return response.bodyToMono(JsonNode.class).defaultIfEmpty(objectMapper.createObjectNode())
                                .flatMap(error -> Mono.error(new BusinessException(status, status,
                                        ERROR_MESSAGES.getOrDefault(error.path("code").asText(), "AI服务请求失败"))));
                    }).block(properties.getConnectTimeout().plus(properties.getReadTimeout()));
            if (result == null || !result.isObject()) throw new StreamFailure("AI_PROTOCOL_ERROR");
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(SERVER_ERROR, 503, "AI服务暂不可用");
        }
    }

    private void requirePersistence() {
        if (!properties.isPersistenceEnabled()) {
            throw new BusinessException(SERVER_ERROR, 503, "AI持久化服务未启用");
        }
    }

    @Override
    public Flux<AiChatEvent> persistentChat(Map<String, Object> payload) {
        requirePersistence();
        Map<String, Object> snapshot = Map.copyOf(payload);
        return Flux.defer(() -> {
            String requestId = UUID.randomUUID().toString();
            Map<String, Object> request = new HashMap<>(snapshot);
            request.put("requestId", requestId);
            return Flux.defer(() -> {
                try {
                    return executeStream(requestId, (String) snapshot.get("sessionId"),
                            "/internal/v1/chat/persistent", objectMapper.writeValueAsBytes(request), true);
                } catch (JsonProcessingException exception) {
                    return Flux.error(new StreamFailure("AI_PROTOCOL_ERROR"));
                }
            }).onErrorResume(error -> {
                String code = error instanceof StreamFailure failure ? failure.code
                        : error instanceof TimeoutException ? "GENERATION_TIMEOUT" : "AI_SERVICE_UNAVAILABLE";
                log.warn("AI持久化流失败: requestId={}, code={}", requestId, code);
                return Flux.just(AiChatEvent.error(requestId, code, ERROR_MESSAGES.get(code)));
            });
        });
    }

    private Flux<AiChatEvent> executeStream(String requestId, String sessionId, String path,
                                            byte[] body, boolean persistent) {
        Flux<ServerSentEvent<String>> events = signedRequest(path, body, requestId)
                .accept(MediaType.TEXT_EVENT_STREAM).exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().thenMany(Flux.error(
                                new StreamFailure("AI_SERVICE_UNAVAILABLE")));
                    }
                    if (!response.headers().contentType().map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                            .orElse(false)) {
                        return response.releaseBody().thenMany(Flux.error(
                                new StreamFailure("AI_PROTOCOL_ERROR")));
                    }
                    return response.bodyToFlux(SSE_TYPE);
                });
        return Flux.defer(() -> {
            long deadline = System.nanoTime() + properties.getStreamTimeout().toNanos();
            return decodeStream(requestId, sessionId, events, persistent)
                    .timeout(properties.getReadTimeout())
                    .timeout(Mono.delay(properties.getStreamTimeout()), event ->
                            Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
        });
    }

    Flux<AiChatEvent> decodeStream(String requestId, String sessionId,
                                  Flux<ServerSentEvent<String>> events) {
        return decodeStream(requestId, sessionId, events, false);
    }

    // 把 Python SSE 转换为固定事件协议，同时识别结束事件与提前断流。
    private Flux<AiChatEvent> decodeStream(String requestId, String sessionId,
                                         Flux<ServerSentEvent<String>> events, boolean persistent) {
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return events.<AiChatEvent>handle((event, sink) -> {
                if (event.data() == null && event.event() == null) return;
                JsonNode data = parseData(event.data());
                switch (event.event() == null ? "" : event.event()) {
                    case "meta" -> {
                        require(!state.meta && requestId.equals(data.path("requestId").asText())
                                && sessionId.equals(data.path("sessionId").asText()));
                        state.meta = true;
                        if (persistent) {
                            require(data.path("messageId").asText().matches("[1-9][0-9]*")
                                    && data.path("userMessageId").asText().matches("[1-9][0-9]*")
                                    && data.path("replayed").isBoolean());
                            sink.next(new AiChatEvent("meta", requestId, sessionId, null, null, null,
                                    data.path("messageId").asText(), data.path("userMessageId").asText(),
                                    data.path("replayed").asBoolean(), data.path("status").asText(),
                                    data.path("model").asText()));
                        }
                    }
                    case "delta" -> {
                        require(state.meta && data.path("content").isTextual()
                                && !data.path("content").asText().isEmpty());
                        state.content = true;
                        sink.next(AiChatEvent.delta(requestId, data.path("content").asText()));
                    }
                    case "done" -> {
                        require(state.meta && state.content
                                && requestId.equals(data.path("requestId").asText()));
                        state.done = true;
                        sink.next(AiChatEvent.done(requestId));
                    }
                    case "recommendations" -> {
                        require(persistent && state.meta && state.content && !state.recommendations
                                && requestId.equals(data.path("requestId").asText()));
                        state.recommendations = true;
                        sink.next(new AiChatEvent("recommendations", requestId, sessionId, null,
                                null, null, null, null, null, null, null, decodeRecommendations(data.path("items"))));
                    }
                    case "error" -> {
                        require(requestId.equals(data.path("requestId").asText()));
                        String code = data.path("code").asText();
                        throw new StreamFailure(ERROR_MESSAGES.containsKey(code) ? code : "MODEL_UPSTREAM_ERROR");
                    }
                    default -> throw new StreamFailure("AI_PROTOCOL_ERROR");
                }
            }).takeUntil(event -> "done".equals(event.type()))
                    .concatWith(Flux.defer(() -> state.done ? Flux.empty()
                            : Flux.error(new StreamFailure("AI_STREAM_INTERRUPTED"))));
        });
    }

    private JsonNode parseData(String data) {
        try {
            JsonNode node = objectMapper.readTree(data == null ? "" : data);
            require(node != null && node.isObject());
            return node;
        } catch (JsonProcessingException exception) {
            throw new StreamFailure("AI_PROTOCOL_ERROR");
        }
    }

    private List<AiChatEvent.Recommendation> decodeRecommendations(JsonNode items) {
        require(items.isArray() && !items.isEmpty() && items.size() <= 5);
        List<AiChatEvent.Recommendation> result = new ArrayList<>();
        var ids = new HashSet<String>();
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            String name = item.path("name").asText();
            require(id.matches("[1-9][0-9]{0,18}") && ids.add(id)
                    && item.path("name").isTextual() && !name.isBlank() && name.length() <= 200);
            try { Long.parseLong(id); }
            catch (NumberFormatException invalidId) { throw new StreamFailure("AI_PROTOCOL_ERROR"); }
            JsonNode reason = item.path("reason");
            require(reason.isMissingNode() || reason.isNull() || (reason.isTextual() && reason.asText().length() <= 500));
            result.add(new AiChatEvent.Recommendation(id, name, reason.isTextual() ? reason.asText() : null));
        }
        return List.copyOf(result);
    }

    private void require(boolean condition) {
        if (!condition) throw new StreamFailure("AI_PROTOCOL_ERROR");
    }

    private static final class StreamState {
        private boolean meta;
        private boolean content;
        private boolean done;
        private boolean recommendations;
    }

    private static final class StreamFailure extends RuntimeException {
        private final String code;
        private StreamFailure(String code) { super(code); this.code = code; }
    }
}
