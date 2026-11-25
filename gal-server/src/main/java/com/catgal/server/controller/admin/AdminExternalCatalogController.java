/*
 * 阅读说明
 * 搜索Bangumi游戏或VNDB视觉小说、预览外部游戏条目、读取外部关联与当前游戏修订号的 HTTP 入口。
 * 路由前缀 /admin，请求绑定后委托 ExternalCatalogService、ExternalLinkStore。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.catalog.external.*;
import com.catgal.server.domain.dto.admin.ExternalGameLinkDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Validated
@Tag(name = "后台管理 - 外部游戏资料")
@SecurityRequirement(name = "bearerAuth")
public class AdminExternalCatalogController {
    private final ExternalCatalogService catalog;
    private final ExternalLinkStore links;

    @GetMapping("/catalog/sources/{source}/search")
    @Operation(summary = "搜索Bangumi游戏或VNDB视觉小说", description = "每页10条，页码1至20，结果短期缓存")
    public R<ExternalSearchResult> search(@PathVariable String source, @RequestParam String keyword,
                                        @RequestParam(defaultValue = "1") int pageNo) {
        return R.ok(catalog.search(ExternalSource.parse(source), keyword, pageNo));
    }

    @GetMapping("/catalog/sources/{source}/items/{externalId}")
    @Operation(summary = "预览外部游戏条目")
    public R<ExternalGamePreview> preview(@PathVariable String source, @PathVariable String externalId) {
        return R.ok(catalog.detail(ExternalSource.parse(source), externalId));
    }

    @GetMapping("/games/{gameId}/external-links")
    @Operation(summary = "读取外部关联与当前游戏修订号")
    public R<ExternalLinkState> list(@PathVariable @Positive long gameId) { return R.ok(links.adminLinks(gameId)); }

    @PutMapping("/games/{gameId}/external-links/{source}")
    @Operation(summary = "人工绑定或替换一个来源条目", description = "先校验外部条目，再在短事务中绑定并递增游戏revision")
    public R<ExternalLinkState> bind(@PathVariable @Positive long gameId, @PathVariable String source,
                                    @Valid @RequestBody ExternalGameLinkDTO dto) {
        ExternalSource provider = ExternalSource.parse(source);
        ExternalGamePreview item = catalog.detail(provider, dto.getExternalId());
        return R.ok(links.bind(gameId, dto.getRevision(), item));
    }

    @DeleteMapping("/games/{gameId}/external-links/{source}")
    @Operation(summary = "解除指定来源关联", description = "revision必须与当前游戏一致")
    public R<ExternalLinkState> unbind(@PathVariable @Positive long gameId, @PathVariable String source,
                                      @RequestParam @Min(0) long revision) {
        return R.ok(links.unbind(gameId, ExternalSource.parse(source), revision));
    }
}
