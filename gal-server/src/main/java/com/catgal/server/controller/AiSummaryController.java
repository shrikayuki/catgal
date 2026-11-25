/*
 * 阅读说明
 * 读取本人会话的滚动摘要及状态、设置本人会话的滚动摘要开关、清除本人会话的滚动摘要的 HTTP 入口。
 * 路由前缀 /ai/sessions/{sessionId}/summary，请求绑定后委托 AiServiceClient、AiServiceProperties。
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai/sessions/{sessionId}/summary")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiSummaryController {
    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public record UpdateSettings(@NotNull @PositiveOrZero Long revision, @NotNull Boolean enabled) { }

    @GetMapping
    @Operation(summary = "读取本人会话的滚动摘要及状态")
    public R<JsonNode> get(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId) {
        return R.ok(client.persistenceRequest("summary/get", payload(sessionId)));
    }

    @PutMapping
    @Operation(summary = "设置本人会话的滚动摘要开关", description = "revision 初始为0，修改时校验并发版本")
    public R<JsonNode> settings(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId,
                                @Valid @RequestBody UpdateSettings body) {
        Map<String, Object> data = payload(sessionId);
        data.put("revision", body.revision());
        data.put("enabled", body.enabled());
        return R.ok(client.persistenceRequest("summary/settings", data));
    }

    @DeleteMapping
    @Operation(summary = "清除本人会话的滚动摘要", description = "revision 校验并发版本")
    public R<JsonNode> clear(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String sessionId,
                             @RequestParam @PositiveOrZero Long revision) {
        Map<String, Object> data = payload(sessionId);
        data.put("revision", revision);
        return R.ok(client.persistenceRequest("summary/clear", data));
    }

    private Map<String, Object> payload(String sessionId) {
        if (!properties.isSessionSummaryEnabled())
            throw new BusinessException(500, 503, "会话摘要功能未启用");
        Long userId = UserContext.getUserId();
        if (userId == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("sessionId", sessionId);
        return data;
    }
}
