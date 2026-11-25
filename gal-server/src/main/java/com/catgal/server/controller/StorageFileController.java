/*
 * 阅读说明
 * 读取当前公开头像、读取游戏封面、读取本人聊天伙伴头像的 HTTP 入口。
 * 路由前缀 /files，请求绑定后委托 IStorageFileService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;

import com.catgal.server.service.IStorageFileService;
import com.catgal.server.storage.AiBotImageKind;
import com.catgal.server.mvc.annotation.LoginRequired;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class StorageFileController {
    private final IStorageFileService files;

    @GetMapping("/avatars/{fileId}")
    @Operation(summary = "读取当前公开头像")
    public ResponseEntity<InputStreamResource> avatar(@PathVariable String fileId) {
        return image(files.openAvatar(fileId));
    }

    @GetMapping("/game-covers/{fileId}")
    @Operation(summary = "读取游戏封面", description = "发布游戏的当前封面可公开读取，其他状态需要实时管理员权限。")
    public ResponseEntity<InputStreamResource> gameCover(@PathVariable String fileId) {
        return image(files.openGameCover(fileId));
    }

    private ResponseEntity<InputStreamResource> image(IStorageFileService.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.size()).cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.content()));
    }

    @GetMapping("/ai-bot-avatars/{fileId}")
    @LoginRequired
    @Operation(summary = "读取本人聊天伙伴头像")
    public ResponseEntity<InputStreamResource> aiBotAvatar(@PathVariable String fileId) {
        return privateImage(files.openAiBotImage(AiBotImageKind.AVATAR, fileId));
    }

    @GetMapping("/ai-bot-portraits/{fileId}")
    @LoginRequired
    @Operation(summary = "读取本人聊天伙伴立绘")
    public ResponseEntity<InputStreamResource> aiBotPortrait(@PathVariable String fileId) {
        return privateImage(files.openAiBotImage(AiBotImageKind.PORTRAIT, fileId));
    }

    private ResponseEntity<InputStreamResource> privateImage(IStorageFileService.Download download) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.size()).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.content()));
    }
}
