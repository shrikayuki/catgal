package com.catgal.server.controller.admin;

import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.domain.dto.admin.AdminStatusUpdateDTO;
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
    @Operation(summary = "分页查询资源审核队列", description = "仅管理员接口返回下载链接和提取信息")
    public R<PageDTO<AdminResourceVO>> pageResources(@Valid AdminPageQuery query) {
        return R.ok(adminCatalogService.pageResources(query));
    }

    @PatchMapping("/resources/{resourceId}/status")
    @Operation(summary = "审核资源", description = "状态：0失效、1有效、2待审核")
    public R<AdminResourceVO> updateResourceStatus(@PathVariable Long resourceId,
                                                   @Valid @RequestBody AdminStatusUpdateDTO dto) {
        return R.ok(adminCatalogService.updateResourceStatus(resourceId, dto.getStatus()));
    }
}
