/*
 * 阅读说明
 * 上传并替换本人聊天伙伴的头像或立绘、移除本人聊天伙伴的头像或立绘的 HTTP 入口。
 * 路由前缀 /ai/bots/{botId}/images/{kind}，请求绑定后委托 IStorageFileService、AiServiceProperties。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.common.domain.R;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.AiServiceProperties;
import com.catgal.server.mvc.annotation.LoginRequired;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.storage.AiBotImageKind;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ai/bots/{botId}/images/{kind}")
@LoginRequired
@Validated
@RequiredArgsConstructor
public class AiBotImageController {
    private final IStorageFileService files;
    private final AiServiceProperties properties;

    public record ImageUpdate(String url, String revision) {}

    @PostMapping
    @Operation(summary = "上传并替换本人聊天伙伴的头像或立绘")
    public R<ImageUpdate> upload(@PathVariable @Positive long botId, @PathVariable String kind,
                                 @RequestParam @Positive Long revision,
                                 @RequestParam("file") MultipartFile file) {
        requireEnabled();
        return response(files.updateAiBotImage(botId, AiBotImageKind.fromPath(kind), revision, file));
    }

    @DeleteMapping
    @Operation(summary = "移除本人聊天伙伴的头像或立绘")
    public R<ImageUpdate> clear(@PathVariable @Positive long botId, @PathVariable String kind,
                                @RequestParam @Positive Long revision) {
        requireEnabled();
        return response(files.clearAiBotImage(botId, AiBotImageKind.fromPath(kind), revision));
    }

    private R<ImageUpdate> response(IStorageFileService.UpdateResult result) {
        // 与 AI 配置接口保持字符串版本，避免浏览器将长整数取整。
        return R.ok(new ImageUpdate(result.url() == null ? "" : result.url(), result.revision().toString()));
    }

    private void requireEnabled() {
        if (!properties.isPersonalizationEnabled()) {
            throw new BusinessException(500, 503, "个人聊天伙伴暂时无法设置");
        }
    }
}
