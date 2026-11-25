/*
 * 阅读说明
 * 收藏、取消收藏的 HTTP 入口。
 * 路由前缀 /favorite，请求绑定后委托 IFavoriteItemService。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.mvc.annotation.LoginRequired;
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
@LoginRequired
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
