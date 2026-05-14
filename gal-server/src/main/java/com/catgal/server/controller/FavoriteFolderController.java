package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.service.IFavoriteFolderService;
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
    @Operation(summary = "创建收藏夹")
    public R<Void> createFolder(@Valid @RequestBody CreateFolderDTO dto) {
        folderService.createFolder(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除收藏夹")
    public R<Void> deleteFolder(@PathVariable Long id) {
        folderService.deleteFolder(id);
        return R.ok();
    }

    @GetMapping("{id}")
    @Operation(summary = "根据id查看收藏夹下的游戏")
    public R<PageDTO<FolderGameVO>> getGamesByFolderId(@PathVariable Long id, PageQuery query) {
        PageDTO<FolderGameVO> vos = folderService.getGamesById(id, query);
        return R.ok(vos);
    }
}
