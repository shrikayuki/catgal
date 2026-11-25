/*
 * 阅读说明
 * 读取本人的机器人记忆向量检索授权、设置本人的机器人记忆向量检索授权的 HTTP 入口。
 * 路由前缀 /ai/bots/{botId}/memory-vector-settings，请求绑定后委托 AiServiceClient、AiServiceProperties。
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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai/bots/{botId}/memory-vector-settings")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiMemoryVectorController {
    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public record UpdateSettings(@NotNull @PositiveOrZero Long revision, @NotNull Boolean enabled) {
        @Override public String toString() { return "UpdateSettings[redacted]"; }
    }

    @GetMapping
    @Operation(summary = "读取本人的机器人记忆向量检索授权")
    public R<JsonNode> settings(@PathVariable @Positive Long botId) {
        return R.ok(client.persistenceRequest("memory-vector/settings/get", payload(botId)));
    }

    @PutMapping
    @Operation(summary = "设置本人的机器人记忆向量检索授权", description = "独立授权，初始revision为0，校验并发版本")
    public R<JsonNode> updateSettings(@PathVariable @Positive Long botId,
                                      @Valid @RequestBody UpdateSettings body) {
        Map<String, Object> data = payload(botId);
        data.put("revision", body.revision());
        data.put("enabled", body.enabled());
        return R.ok(client.persistenceRequest("memory-vector/settings/update", data));
    }

    private Map<String, Object> payload(Long botId) {
        if (!properties.isMemoryVectorEnabled())
            throw new BusinessException(500, 503, "记忆向量检索功能未启用");
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("botId", botId);
        return data;
    }
}
