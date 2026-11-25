/*
 * 阅读说明
 * 读取本人的机器人记忆候选设置、设置本人的机器人记忆候选开关、游标查询本人的机器人记忆候选的 HTTP 入口。
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
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.validation.annotation.Validated;
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
public class AiMemoryCandidateController {
    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public record UpdateSettings(@NotNull @PositiveOrZero Long revision, @NotNull Boolean enabled) { }

    public record ConfirmCandidate(@NotNull @Positive Long revision,
                                    @NotBlank @CodePointLength(max = 1000) String content,
                                    @NotNull @Pattern(regexp = "preference|fact") String memoryType) {
        @Override public String toString() { return "ConfirmCandidate[redacted]"; }
    }

    public record RejectCandidate(@NotNull @Positive Long revision) { }

    @GetMapping("/memory-candidate-settings")
    @Operation(summary = "读取本人的机器人记忆候选设置")
    public R<JsonNode> settings(@PathVariable @Positive Long botId) {
        return R.ok(client.persistenceRequest("memory-candidates/settings/get", payload(botId)));
    }

    @PutMapping("/memory-candidate-settings")
    @Operation(summary = "设置本人的机器人记忆候选开关", description = "初始revision为0，校验并发版本")
    public R<JsonNode> updateSettings(@PathVariable @Positive Long botId,
                                      @Valid @RequestBody UpdateSettings body) {
        Map<String, Object> data = payload(botId);
        data.put("revision", body.revision());
        data.put("enabled", body.enabled());
        return R.ok(client.persistenceRequest("memory-candidates/settings/update", data));
    }

    @GetMapping("/memory-candidates")
    @Operation(summary = "游标查询本人的机器人记忆候选")
    public R<JsonNode> list(@PathVariable @Positive Long botId,
                            @RequestParam(required = false) @Positive Long beforeId,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Map<String, Object> data = payload(botId);
        if (beforeId != null) data.put("beforeId", beforeId);
        data.put("limit", limit);
        return R.ok(client.persistenceRequest("memory-candidates/list", data));
    }

    @PostMapping("/memory-candidates/{candidateId}/confirm")
    @Operation(summary = "确认本人机器人的记忆候选", description = "可编辑内容后保存为长期记忆")
    public R<JsonNode> confirm(@PathVariable @Positive Long botId,
                               @PathVariable @Positive Long candidateId,
                               @Valid @RequestBody ConfirmCandidate body) {
        Map<String, Object> data = candidatePayload(botId, candidateId);
        data.put("revision", body.revision());
        data.put("content", body.content());
        data.put("memoryType", body.memoryType());
        return R.ok(client.persistenceRequest("memory-candidates/confirm", data));
    }

    @PostMapping("/memory-candidates/{candidateId}/reject")
    @Operation(summary = "拒绝本人机器人的记忆候选")
    public R<JsonNode> reject(@PathVariable @Positive Long botId,
                              @PathVariable @Positive Long candidateId,
                              @Valid @RequestBody RejectCandidate body) {
        Map<String, Object> data = candidatePayload(botId, candidateId);
        data.put("revision", body.revision());
        return R.ok(client.persistenceRequest("memory-candidates/reject", data));
    }

    private Map<String, Object> candidatePayload(Long botId, Long candidateId) {
        Map<String, Object> data = payload(botId);
        data.put("candidateId", candidateId);
        return data;
    }

    private Map<String, Object> payload(Long botId) {
        if (!properties.isMemoryCandidatesEnabled())
            throw new BusinessException(500, 503, "记忆候选功能未启用");
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("botId", botId);
        return data;
    }
}
