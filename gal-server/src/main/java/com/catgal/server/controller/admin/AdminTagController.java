/*
 * 阅读说明
 * 分页查询标签、查询标签详情及revision、创建标签的 HTTP 入口。
 * 路由前缀 /admin/tags，请求绑定后委托 ITagService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.service.ITagService;
import com.catgal.server.domain.dto.admin.CatalogNameDTO;
import com.catgal.server.domain.dto.admin.CatalogNameUpdateDTO;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
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
@RequestMapping("/admin/tags")
@RequiredArgsConstructor
@Validated
@Tag(name = "后台管理 - 标签资料")
@SecurityRequirement(name = "bearerAuth")
public class AdminTagController {
    private final ITagService tagService;

    @GetMapping
    @Operation(summary = "分页查询标签")
    public R<PageDTO<CatalogEntryVO>> page(@Valid CatalogPageQuery query) {
        return R.ok(tagService.pageCatalog(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询标签详情及revision")
    public R<CatalogEntryVO> get(@PathVariable @Positive long id) {
        return R.ok(tagService.getCatalog(id));
    }

    @PostMapping
    @Operation(summary = "创建标签", description = "同名冲突返回409")
    public R<CatalogEntryVO> create(@Valid @RequestBody CatalogNameDTO dto) {
        return R.ok(tagService.createCatalog(dto.getName()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改标签名称", description = "revision须与读取值一致，否则返回409")
    public R<CatalogEntryVO> update(@PathVariable @Positive long id, @Valid @RequestBody CatalogNameUpdateDTO dto) {
        return R.ok(tagService.updateCatalog(id, dto.getName(), dto.getRevision()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除未被引用的标签", description = "revision取自详情。被游戏引用或版本过期返回409")
    public R<Void> delete(@PathVariable @Positive long id, @RequestParam @Min(0) Long revision) {
        tagService.deleteCatalog(id, revision);
        return R.ok();
    }
}
