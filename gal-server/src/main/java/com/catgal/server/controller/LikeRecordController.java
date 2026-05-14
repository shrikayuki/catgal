package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.LikeDTO;
import com.catgal.server.domain.po.LikeRecord;
import com.catgal.server.service.ILikeRecordService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 点赞记录表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-03
 */
@RestController
@RequestMapping("/like")
@RequiredArgsConstructor
public class LikeRecordController {

    private final ILikeRecordService likeService;

    @PostMapping
    @Operation(summary = "点赞")
    public R<Boolean> like(@RequestBody LikeDTO dto) {
        return R.ok(likeService.like(dto));
    }

    @DeleteMapping
    @Operation(summary = "取消点赞")
    public R<Boolean> unlike(@RequestBody LikeDTO dto) {
        return R.ok(likeService.unlike(dto));
    }


}


