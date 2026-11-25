/*
 * 阅读说明
 * 查询已发布游戏的标签的 HTTP 入口。
 * 路由前缀 /game-tag，请求绑定后委托 GameCatalogService。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.catgal.common.domain.R;
import com.catgal.server.catalog.CatalogKind;
import com.catgal.server.catalog.GameCatalogService;
import com.catgal.server.domain.vo.CatalogEntryVO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * <p>
 * 游戏-标签关联表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@RestController
@RequestMapping("/game-tag")
@RequiredArgsConstructor
public class GameTagController {
    private final GameCatalogService games;

    @GetMapping("/{gameId}")
    @Operation(summary = "查询已发布游戏的标签")
    public R<List<CatalogEntryVO>> tags(@PathVariable long gameId) {
        return R.ok(games.publicRelations(CatalogKind.TAG, gameId));
    }
}
