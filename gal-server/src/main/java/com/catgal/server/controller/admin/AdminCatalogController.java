/*
 * 阅读说明
 * 分页查询全部状态的游戏、发布或下架游戏、分页查询资源审核队列的 HTTP 入口。
 * 路由前缀 /admin，请求绑定后委托 AdminCatalogService。
 * 管理员访问由 /admin 拦截器链执行，复杂状态与事务规则位于被调用的 Service。
 */
package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminStatusUpdateDTO;
import com.catgal.server.domain.dto.admin.ResourceReviewDTO;
import com.catgal.server.service.IResourceService;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminResourceVO;
import com.catgal.server.service.admin.AdminCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "后台管理 - 游戏与资源审核")
@SecurityRequirement(name = "bearerAuth")
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;
    private final IResourceService resourceService;

    @GetMapping("/games")
    @Operation(summary = "分页查询全部状态的游戏")
    public R<PageDTO<Game>> pageGames(@Valid AdminPageQuery query) {
        return R.ok(adminCatalogService.pageGames(query));
    }

    @PatchMapping("/games/{gameId}/status")
    @Operation(summary = "发布或下架游戏", description = "状态：0草稿、1发布、2下架")
    public R<Game> updateGameStatus(@PathVariable Long gameId,
                                    @Valid @RequestBody AdminStatusUpdateDTO dto) {
        return R.ok(adminCatalogService.updateGameStatus(gameId, dto.getStatus()));
    }

    @GetMapping("/resources")
    @Operation(summary = "分页查询资源审核队列", description = "keyword按资源名称搜索，可组合status、gameId、userId筛选。启用资源ES索引后优先检索ES，故障时回退MySQL。管理员响应包含下载链接和提取信息。")
    public R<PageDTO<AdminResourceVO>> pageResources(@Valid AdminPageQuery query) {
        return R.ok(adminCatalogService.pageResources(query));
    }

    @PatchMapping("/resources/{resourceId}/status")
    @Operation(summary = "审核资源", description = "状态0失效、1有效、2待审。必填revision，失效原因必填。版本过期返回409。")
    public R<AdminResourceVO> updateResourceStatus(@PathVariable Long resourceId,
                                                   @Valid @RequestBody ResourceReviewDTO dto) {
        return R.ok(resourceService.reviewResource(resourceId, dto));
    }
    @GetMapping("/resources/{resourceId}/history")
    @Operation(summary = "查看资源编辑与审核历史")
    public R<PageDTO<ResourceReviewHistory>> resourceHistory(@PathVariable Long resourceId, @Valid AdminPageQuery query) {
        return R.ok(resourceService.history(resourceId, query));
    }
}
