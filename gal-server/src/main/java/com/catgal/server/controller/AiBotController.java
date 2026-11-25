/*
 * 阅读说明
 * 查询平台机器人模板、查询服务器允许的模型来源、获得当前用户的平台机器人实例的 HTTP 入口。
 * 路由前缀 /ai，请求绑定后委托 AiServiceClient、AiServiceProperties。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.common.context.UserContext;
import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.AiServiceClient;
import com.catgal.server.ai.AiServiceProperties;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiBotController {
    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public record CredentialCreate(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{0,31}") String providerCode,
            @NotBlank @Size(max = 64) String label,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotBlank @Pattern(regexp = "[!-~]{8,1024}") String apiKey) {
        public CredentialCreate { if (apiKey != null) apiKey = apiKey.trim(); }
        @Override public String toString() { return "CredentialCreate[redacted]"; }
    }

    public record CredentialRotate(
            @NotNull @Positive Long revision,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotBlank @Pattern(regexp = "[!-~]{8,1024}") String apiKey) {
        public CredentialRotate { if (apiKey != null) apiKey = apiKey.trim(); }
        @Override public String toString() { return "CredentialRotate[redacted]"; }
    }

    public record CreateBot(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String girlCode,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 4000) String persona,
            @Size(max = 1024) String avatarUrl,
            @Size(max = 1024) String portraitUrl,
            @NotNull @Positive Long credentialId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}") String model) { }

    public record UpdateBot(
            @NotNull @Positive Long revision,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 4000) String persona,
            @Size(max = 1024) String avatarUrl,
            @Size(max = 1024) String portraitUrl,
            @Positive Long credentialId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}") String model) { }

    @GetMapping("/templates")
    @Operation(summary = "查询平台机器人模板")
    public R<JsonNode> templates() { return call("templates/list", payload()); }

    @GetMapping("/providers")
    @Operation(summary = "查询服务器允许的模型来源")
    public R<JsonNode> providers() { return call("providers/list", payload()); }

    @PostMapping("/bots/platform/{girlCode}")
    @Operation(summary = "获得当前用户的平台机器人实例")
    public R<JsonNode> platform(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String girlCode) {
        Map<String, Object> data = payload();
        data.put("girlCode", girlCode);
        return call("bots/platform", data);
    }

    @GetMapping("/bots")
    @Operation(summary = "游标查询自己的机器人")
    public R<JsonNode> list(@RequestParam(required = false) @Positive Long beforeId,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return call("bots/list", page(beforeId, limit));
    }

    @PostMapping("/bots")
    @Operation(summary = "创建个人机器人并绑定自己的模型凭据")
    public R<JsonNode> create(@Valid @RequestBody CreateBot body) {
        Map<String, Object> data = payload();
        data.put("girlCode", body.girlCode());
        putAppearance(data, body.name(), body.persona(), body.avatarUrl(), body.portraitUrl());
        data.put("credentialId", body.credentialId());
        data.put("model", body.model());
        return call("bots/create", data);
    }

    @GetMapping("/bots/{botId}")
    @Operation(summary = "查看自己的机器人形象和配置")
    public R<JsonNode> get(@PathVariable @Positive Long botId) { return call("bots/get", botPayload(botId)); }

    @PutMapping("/bots/{botId}")
    @Operation(summary = "更新机器人并保存人设版本")
    public R<JsonNode> update(@PathVariable @Positive Long botId, @Valid @RequestBody UpdateBot body) {
        Map<String, Object> data = botPayload(botId);
        data.put("revision", body.revision());
        putAppearance(data, body.name(), body.persona(), body.avatarUrl(), body.portraitUrl());
        data.put("credentialId", body.credentialId());
        data.put("model", body.model());
        return call("bots/update", data);
    }

    @DeleteMapping("/bots/{botId}")
    @Operation(summary = "归档自己的个人机器人", description = "保留历史。生成中的机器人须先结束会话生成")
    public R<JsonNode> delete(@PathVariable @Positive Long botId, @RequestParam @Positive Long revision) {
        Map<String, Object> data = botPayload(botId);
        data.put("revision", revision);
        return call("bots/delete", data);
    }

    @GetMapping("/bots/{botId}/prompt-versions")
    @Operation(summary = "游标查询自己的人设版本")
    public R<JsonNode> prompts(@PathVariable @Positive Long botId,
                               @RequestParam(required = false) @Positive Long beforeId,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        Map<String, Object> data = page(beforeId, limit);
        data.put("botId", botId);
        return call("bots/prompts", data);
    }

    @GetMapping("/credentials")
    @Operation(summary = "查询自己的模型凭据摘要", description = "仅返回名称、来源、末四位和版本")
    public R<JsonNode> credentials(@RequestParam(required = false) @Positive Long beforeId,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return call("credentials/list", page(beforeId, limit));
    }

    @PostMapping("/credentials")
    @Operation(summary = "加密保存自己的模型 API Key")
    public R<JsonNode> createCredential(@Valid @RequestBody CredentialCreate body) {
        Map<String, Object> data = payload();
        data.put("providerCode", body.providerCode());
        data.put("label", body.label());
        data.put("apiKey", body.apiKey());
        return call("credentials/create", data);
    }

    @PutMapping("/credentials/{credentialId}/key")
    @Operation(summary = "轮换自己的模型 API Key")
    public R<JsonNode> rotate(@PathVariable @Positive Long credentialId,
                              @Valid @RequestBody CredentialRotate body) {
        Map<String, Object> data = payload();
        data.put("credentialId", credentialId);
        data.put("revision", body.revision());
        data.put("apiKey", body.apiKey());
        return call("credentials/rotate", data);
    }

    @DeleteMapping("/credentials/{credentialId}")
    @Operation(summary = "撤销模型凭据并清除密文")
    public R<JsonNode> revoke(@PathVariable @Positive Long credentialId,
                              @RequestParam @Positive Long revision) {
        Map<String, Object> data = payload();
        data.put("credentialId", credentialId);
        data.put("revision", revision);
        return call("credentials/revoke", data);
    }

    private void putAppearance(Map<String, Object> data, String name, String persona, String avatar, String portrait) {
        data.put("name", name);
        data.put("persona", persona == null ? "" : persona);
        data.put("avatarUrl", avatar == null ? "" : avatar);
        data.put("portraitUrl", portrait == null ? "" : portrait);
    }

    private Map<String, Object> botPayload(Long botId) {
        Map<String, Object> data = payload();
        data.put("botId", botId);
        return data;
    }

    private Map<String, Object> page(Long beforeId, int limit) {
        Map<String, Object> data = payload();
        if (beforeId != null) data.put("beforeId", beforeId);
        data.put("limit", limit);
        return data;
    }

    private Map<String, Object> payload() {
        if (!properties.isPersonalizationEnabled())
            throw new BusinessException(500, 503, "个人机器人功能未启用");
        Long user = UserContext.getUserId();
        if (user == null) throw BusinessException.unauthorized("用户未登录");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user);
        return data;
    }

    private R<JsonNode> call(String operation, Map<String, Object> data) {
        return R.ok(client.persistenceRequest(operation, data));
    }
}
