package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.server.service.IGameService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 游戏主表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/game")
@RequiredArgsConstructor
public class GameController {

    private final IGameService gameService;

    @GetMapping("/page")
    @Operation(summary = "分页查询游戏")
    public R<PageDTO<GameVO>> pageGames(@Valid GamePageQuery query) {
        PageDTO<GameVO> page = gameService.pageGames(query);
        return R.ok(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据游戏id查游戏信息")
    public R<GameVO> getGameById(@PathVariable Long id) {
        GameVO vo = gameService.getGameById(id);
        return R.ok(vo);
    }

    @GetMapping("/random")
    @Operation(summary = "随机一部游戏")
    public R<GameVO> getRandomGame() {
        GameVO vo = gameService.getRandomGame();
        return R.ok(vo);
    }


}
