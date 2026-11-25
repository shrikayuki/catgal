/*
 * 阅读说明
 * 创建持久化 AI 会话、按创建顺序游标查询本人的 AI 会话、读取本人会话及生成状态的 HTTP 入口。
 * 路由前缀 /ai，请求绑定后委托 AiServiceClient。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.AiChatEvent;
import com.catgal.server.ai.AiServiceClient;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiSessionController {
    private final AiServiceClient aiServiceClient;

    public record CreateSession(
            @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String girlCode,
            @Positive Long botId,
            @Size(min = 1, max = 100) String title) {
        @AssertTrue(message = "需要提供 girlCode 或 botId，二者选择一个")
        public boolean isTargetValid() { return (girlCode == null) != (botId == null); }
    }

    public record SendMessage(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String clientMessageId,
            @NotBlank @Size(max = 4000) String content, Boolean useInterests, Boolean useGameTools,
            Boolean useSemanticSearch) {
        @AssertTrue(message = "语义检索需要启用游戏查询工具")
        public boolean isSemanticSearchValid() {
            return !Boolean.TRUE.equals(useSemanticSearch) || Boolean.TRUE.equals(useGameTools);
        }
    }

    public record RegenerateMessage(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String clientMessageId,
            Boolean useInterests, Boolean useGameTools, Boolean useSemanticSearch) {
        @AssertTrue(message = "语义检索需要启用游戏查询工具")
        public boolean isSemanticSearchValid() {
            return !Boolean.TRUE.equals(useSemanticSearch) || Boolean.TRUE.equals(useGameTools);
        }
    }

    @PostMapping("/sessions")
    @Operation(summary = "创建持久化 AI 会话")
    public R<JsonNode> create(@Valid @RequestBody CreateSession body) {
        Map<String, Object> payload = userPayload();
        if (body.girlCode() != null) payload.put("girlCode", body.girlCode());
        if (body.botId() != null) payload.put("botId", body.botId());
        if (body.title() != null) payload.put("title", body.title());
        return R.ok(aiServiceClient.persistenceRequest("sessions/create", payload));
    }

    @GetMapping("/sessions")
    @Operation(summary = "按创建顺序游标查询本人的 AI 会话")
    public R<JsonNode> list(@RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String girlCode,
                            @RequestParam(required = false) @Positive Long botId,
                            @RequestParam(required = false) @Positive Long beforeId,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Map<String, Object> payload = pagePayload(beforeId, limit);
        if (girlCode != null) payload.put("girlCode", girlCode);
        if (botId != null) payload.put("botId", botId);
        return R.ok(aiServiceClient.persistenceRequest("sessions/list", payload));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "读取本人会话及生成状态")
    public R<JsonNode> get(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId) {
        return R.ok(aiServiceClient.persistenceRequest("sessions/get", sessionPayload(sessionId)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "软删除本人会话并终止其写入资格")
    public R<JsonNode> delete(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId) {
        return R.ok(aiServiceClient.persistenceRequest("sessions/delete", sessionPayload(sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "游标读取消息历史", description = "页内按时间正序，nextCursor 用于读取更早的消息")
    public R<JsonNode> messages(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId,
                                @RequestParam(required = false) @Positive Long beforeId,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Map<String, Object> payload = pagePayload(beforeId, limit);
        payload.put("sessionId", sessionId);
        return R.ok(aiServiceClient.persistenceRequest("messages/list", payload));
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "幂等发送消息并流式生成", description = "同一用户的 clientMessageId 须唯一。重复请求回放已保存结果")
    public Flux<ServerSentEvent<AiChatEvent>> send(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId,
            @Valid @RequestBody SendMessage body, HttpServletResponse response) {
        Map<String, Object> payload = sessionPayload(sessionId);
        payload.put("clientMessageId", body.clientMessageId());
        payload.put("content", body.content());
        payload.put("useInterests", Boolean.TRUE.equals(body.useInterests()));
        payload.put("useGameTools", Boolean.TRUE.equals(body.useGameTools()));
        if (Boolean.TRUE.equals(body.useSemanticSearch())) payload.put("useSemanticSearch", true);
        return stream(payload, response);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages/{messageId}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "重新生成最近一轮回答", description = "保留旧回答，新 clientMessageId 对应一次新的生成")
    public Flux<ServerSentEvent<AiChatEvent>> regenerate(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId,
            @PathVariable @Positive Long messageId,
            @Valid @RequestBody RegenerateMessage body, HttpServletResponse response) {
        Map<String, Object> payload = sessionPayload(sessionId);
        payload.put("clientMessageId", body.clientMessageId());
        payload.put("regenerateMessageId", messageId);
        payload.put("useInterests", Boolean.TRUE.equals(body.useInterests()));
        payload.put("useGameTools", Boolean.TRUE.equals(body.useGameTools()));
        if (Boolean.TRUE.equals(body.useSemanticSearch())) payload.put("useSemanticSearch", true);
        return stream(payload, response);
    }

    private Flux<ServerSentEvent<AiChatEvent>> stream(Map<String, Object> payload,
                                                      HttpServletResponse response) {
        // Capture identity and check ownership before the servlet starts its SSE response.
        aiServiceClient.persistenceRequest("sessions/get",
                Map.of("userId", payload.get("userId"), "sessionId", payload.get("sessionId")));
        Flux<AiChatEvent> events = aiServiceClient.persistentChat(payload);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Content-Type-Options", "nosniff");
        return events.map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }

    private Map<String, Object> sessionPayload(String sessionId) {
        Map<String, Object> payload = userPayload();
        payload.put("sessionId", sessionId);
        return payload;
    }

    private Map<String, Object> pagePayload(Long beforeId, int limit) {
        Map<String, Object> payload = userPayload();
        payload.put("limit", limit);
        if (beforeId != null) payload.put("beforeId", beforeId);
        return payload;
    }

    private Map<String, Object> userPayload() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        return payload;
    }
}
