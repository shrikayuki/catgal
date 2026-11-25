/*
 * 阅读说明
 * 分页查询会社资料、查询会社资料、查询已发布游戏的关联会社的 HTTP 入口。
 * 路由前缀 /company，请求绑定后委托 ICompanyService、GameCatalogService。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.dto.PageDTO;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import com.catgal.common.domain.R;
import com.catgal.server.service.ICompanyService;
import com.catgal.server.catalog.CatalogKind;
import com.catgal.server.catalog.GameCatalogService;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * <p>
 * 游戏-会社关联表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@RestController
@RequestMapping("/company")
@RequiredArgsConstructor
public class GameCompanyController {
    private final ICompanyService companyService;
    private final GameCatalogService games;

    @GetMapping("/page")
    @Operation(summary = "分页查询会社资料")
    public R<PageDTO<CatalogEntryVO>> page(@Valid CatalogPageQuery query) {
        return R.ok(companyService.pageCatalog(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询会社资料")
    public R<CatalogEntryVO> get(@PathVariable long id) {
        return R.ok(companyService.getCatalog(id));
    }

    @GetMapping("/game/{gameId}")
    @Operation(summary = "查询已发布游戏的关联会社")
    public R<List<CatalogEntryVO>> gameCompanies(@PathVariable long gameId) {
        return R.ok(games.publicRelations(CatalogKind.COMPANY, gameId));
    }
}
