package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameReviewVO;
import com.catgal.server.domain.po.Game;
import com.catgal.server.service.IGameReviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Delete;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 游戏评价表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class GameReviewController {

    private final IGameReviewService reviewService;

    @GetMapping("/page")
    @Operation(summary = "分页查询游戏下的评价列表")
    public R<GameConnectVO<GameReviewVO>> pageGameReview(GameQuery query){
        GameConnectVO<GameReviewVO> vo = reviewService.pageGameReview(query);
        return R.ok(vo);
    }

    @PostMapping()
    @Operation(summary = "发布游戏评价")
    public R<Void> addGameReview(@Valid @RequestBody ReviewAddDTO dto){
        reviewService.addGameReview(dto);
        return R.ok();
    }

    @DeleteMapping("{id}")
    @Operation(summary = "删除用户评价")
    public R<Void> deleteGameReview(@PathVariable("id") Long id){
        reviewService.deleteGameReview(id);
        return R.ok();
    }
}
