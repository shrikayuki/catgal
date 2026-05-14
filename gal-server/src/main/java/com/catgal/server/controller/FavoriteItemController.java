package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.server.service.IFavoriteItemService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 收藏表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/favorite")
@RequiredArgsConstructor
public class FavoriteItemController {

    private final IFavoriteItemService favoriteService;

    @PostMapping
    @Operation(summary = "收藏")
    public R<Boolean> favorite(@Valid @RequestBody FavoriteDTO dto) {
        return R.ok(favoriteService.favorite(dto));
    }

    @DeleteMapping
    @Operation(summary = "取消收藏")
    public R<Boolean> unfavorite(@Valid @RequestBody FavoriteDTO dto) {
        return R.ok(favoriteService.unfavorite(dto));
    }
}
