/*
 * 阅读说明
 * 查询本人的机器人记忆设置、设置本人的机器人长期记忆开关、游标查询本人的机器人记忆的 HTTP 入口。
 * 路由前缀 /ai/bots/{botId}，请求绑定后委托 AiServiceClient、AiServiceProperties。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.AiServiceClient;
import com.catgal.server.ai.AiServiceProperties;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai/bots/{botId}")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiMemoryController {
    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public record UpdateSettings(@NotNull @Positive Long revision, @NotNull Boolean memoryEnabled) { }

    public record CreateMemory(@NotBlank @CodePointLength(max = 1000) String content,
                                @NotNull @Pattern(regexp = "preference|fact") String memoryType,
                                @Positive Long sourceMessageId) {
        @Override public String toString() { return "CreateMemory[redacted]"; }
    }

    public record UpdateMemory(@NotNull @Positive Long revision,
                                @NotBlank @CodePointLength(max = 1000) String content,
                                @NotNull @Pattern(regexp = "preference|fact") String memoryType) {
        @Override public String toString() { return "UpdateMemory[redacted]"; }
    }

    @GetMapping("/memory-settings")
    @Operation(summary = "查询本人的机器人记忆设置")
    public R<JsonNode> settings(@PathVariable @Positive Long botId) {
        return R.ok(client.persistenceRequest("memory/settings/get", payload(botId)));
    }

    @PutMapping("/memory-settings")
    @Operation(summary = "设置本人的机器人长期记忆开关")
    public R<JsonNode> updateSettings(@PathVariable @Positive Long botId,
                                      @Valid @RequestBody UpdateSettings body) {
        Map<String, Object> data = payload(botId);
        data.put("revision", body.revision());
        data.put("memoryEnabled", body.memoryEnabled());
        return R.ok(client.persistenceRequest("memory/settings/update", data));
    }

    @GetMapping("/memories")
    @Operation(summary = "游标查询本人的机器人记忆")
    public R<JsonNode> list(@PathVariable @Positive Long botId,
                            @RequestParam(required = false) @Positive Long beforeId,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Map<String, Object> data = payload(botId);
        if (beforeId != null) data.put("beforeId", beforeId);
        data.put("limit", limit);
        return R.ok(client.persistenceRequest("memory/list", data));
    }

    @PostMapping("/memories")
    @Operation(summary = "保存本人的机器人偏好或事实记忆")
    public R<JsonNode> create(@PathVariable @Positive Long botId,
                              @Valid @RequestBody CreateMemory body) {
        Map<String, Object> data = payload(botId);
        data.put("content", body.content());
        data.put("memoryType", body.memoryType());
        if (body.sourceMessageId() != null) data.put("sourceMessageId", body.sourceMessageId());
        return R.ok(client.persistenceRequest("memory/create", data));
    }

    @PutMapping("/memories/{memoryId}")
    @Operation(summary = "修改本人的机器人记忆", description = "revision 校验并发修改")
    public R<JsonNode> update(@PathVariable @Positive Long botId,
                              @PathVariable @Positive Long memoryId,
                              @Valid @RequestBody UpdateMemory body) {
        Map<String, Object> data = memoryPayload(botId, memoryId);
        data.put("revision", body.revision());
        data.put("content", body.content());
        data.put("memoryType", body.memoryType());
        return R.ok(client.persistenceRequest("memory/update", data));
    }

    @DeleteMapping("/memories/{memoryId}")
    @Operation(summary = "删除本人的机器人记忆")
    public R<JsonNode> delete(@PathVariable @Positive Long botId,
                              @PathVariable @Positive Long memoryId,
                              @RequestParam @Positive Long revision) {
        Map<String, Object> data = memoryPayload(botId, memoryId);
        data.put("revision", revision);
        return R.ok(client.persistenceRequest("memory/delete", data));
    }

    private Map<String, Object> memoryPayload(Long botId, Long memoryId) {
        Map<String, Object> data = payload(botId);
        data.put("memoryId", memoryId);
        return data;
    }

    private Map<String, Object> payload(Long botId) {
        if (!properties.isLongTermMemoryEnabled())
            throw new BusinessException(500, 503, "长期记忆功能未启用");
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("botId", botId);
        return data;
    }
}
