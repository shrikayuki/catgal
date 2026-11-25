/*
 * 阅读说明
 * 分页查询标签资料、查询标签资料的 HTTP 入口。
 * 路由前缀 /tag，请求绑定后委托 ITagService。
 * 参数注解决定路径、查询或正文绑定方式，业务可见性与数据组装在服务层核验。
 */
package com.catgal.server.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.server.service.ITagService;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.domain.vo.CatalogEntryVO;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 游戏标签表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-30
 */
@RestController
@RequestMapping("/tag")
@RequiredArgsConstructor
public class TagController {
    private final ITagService tagService;

    @GetMapping("/page")
    @Operation(summary = "分页查询标签资料")
    public R<PageDTO<CatalogEntryVO>> page(@Valid CatalogPageQuery query) {
        return R.ok(tagService.pageCatalog(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询标签资料")
    public R<CatalogEntryVO> get(@PathVariable long id) {
        return R.ok(tagService.getCatalog(id));
    }
}
