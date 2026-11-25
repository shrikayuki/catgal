/*
 * 阅读说明
 * 上传并替换托管游戏封面、清除游戏封面的 HTTP 入口。
 * 路由前缀 /admin/games/{gameId}/cover，请求绑定后委托 IStorageFileService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.service.IStorageFileService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/games/{gameId}/cover")
@RequiredArgsConstructor
@Validated
public class AdminGameCoverController {
    private final IStorageFileService files;

    @PostMapping
    @Operation(summary = "上传并替换托管游戏封面", description = "需要当前游戏revision。成功同时更新封面引用及revision。")
    public R<IStorageFileService.UpdateResult> upload(@PathVariable @Positive long gameId,
                                                     @RequestParam @Min(0) Long revision,
                                                     @RequestParam("file") MultipartFile file) {
        return R.ok(files.updateGameCover(gameId, revision, file));
    }

    @DeleteMapping
    @Operation(summary = "清除游戏封面", description = "需要当前revision，原托管对象进入持久化清理队列。")
    public R<IStorageFileService.UpdateResult> clear(@PathVariable @Positive long gameId,
                                                    @RequestParam @Min(0) Long revision) {
        return R.ok(files.clearGameCover(gameId, revision));
    }
}
