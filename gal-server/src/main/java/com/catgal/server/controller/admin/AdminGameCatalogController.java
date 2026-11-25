/*
 * 阅读说明
 * 读取任意状态游戏的基础资料、revision及标签会社关联、创建游戏草稿、完整替换游戏资料与标签会社关联的 HTTP 入口。
 * 路由前缀 /admin/games，请求绑定后委托 GameCatalogService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.server.catalog.CatalogKind;
import com.catgal.server.catalog.GameCatalogService;
import com.catgal.server.domain.dto.admin.CatalogRelationsUpdateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogCreateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogUpdateDTO;
import com.catgal.server.domain.vo.admin.AdminGameDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/games")
@RequiredArgsConstructor
@Validated
@Tag(name = "后台管理 - 游戏资料维护")
@SecurityRequirement(name = "bearerAuth")
public class AdminGameCatalogController {
    private final GameCatalogService catalog;

    @GetMapping("/{gameId}")
    @Operation(summary = "读取任意状态游戏的基础资料、revision及标签会社关联")
    public R<AdminGameDetailVO> get(@PathVariable @Positive long gameId) {
        return R.ok(catalog.detail(gameId));
    }

    @PostMapping
    @Operation(summary = "创建游戏草稿", description = "创建资料和关联使用同一事务。发布使用现有status接口。")
    public R<AdminGameDetailVO> create(@Valid @RequestBody GameCatalogCreateDTO dto) {
        return R.ok(catalog.create(dto));
    }

    @PutMapping("/{gameId}")
    @Operation(summary = "完整替换游戏资料与标签会社关联", description = "revision取自详情。空集合清空对应关联，版本过期返回409。")
    public R<AdminGameDetailVO> update(@PathVariable @Positive long gameId, @Valid @RequestBody GameCatalogUpdateDTO dto) {
        return R.ok(catalog.update(gameId, dto));
    }

    @PutMapping("/{gameId}/tags")
    @Operation(summary = "完整替换游戏标签", description = "最多50个标签。空ids集合解绑全部，游戏revision也会更新。")
    public R<AdminGameDetailVO> tags(@PathVariable @Positive long gameId, @Valid @RequestBody CatalogRelationsUpdateDTO dto) {
        return R.ok(catalog.replaceRelations(CatalogKind.TAG, gameId, dto));
    }

    @PutMapping("/{gameId}/companies")
    @Operation(summary = "完整替换游戏会社", description = "最多50个会社。空ids集合解绑全部，游戏revision也会更新。")
    public R<AdminGameDetailVO> companies(@PathVariable @Positive long gameId, @Valid @RequestBody CatalogRelationsUpdateDTO dto) {
        return R.ok(catalog.replaceRelations(CatalogKind.COMPANY, gameId, dto));
    }
}
