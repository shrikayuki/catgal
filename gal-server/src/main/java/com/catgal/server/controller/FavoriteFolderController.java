/*
 * 阅读说明
 * 创建收藏夹、删除收藏夹、根据id查看收藏夹下的游戏的 HTTP 入口。
 * 路由前缀 /folder，请求绑定后委托 IFavoriteFolderService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.server.service.IFavoriteFolderService;
import com.catgal.server.mvc.annotation.LoginRequired;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 收藏夹表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/folder")
@RequiredArgsConstructor
@Tag(name = "收藏夹管理")
public class FavoriteFolderController {

    private final IFavoriteFolderService folderService;

    @PostMapping
    @LoginRequired
    @Operation(summary = "创建收藏夹")
    public R<Void> createFolder(@Valid @RequestBody CreateFolderDTO dto) {
        folderService.createFolder(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    @Operation(summary = "删除收藏夹")
    public R<Void> deleteFolder(@PathVariable Long id) {
        folderService.deleteFolder(id);
        return R.ok();
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据id查看收藏夹下的游戏")
    public R<PageDTO<FolderGameVO>> getGamesByFolderId(@PathVariable Long id, PageQuery query) {
        PageDTO<FolderGameVO> vos = folderService.getGamesById(id, query);
        return R.ok(vos);
    }
}
