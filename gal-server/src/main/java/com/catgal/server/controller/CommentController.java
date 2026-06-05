package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.CommentVO;

import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.UserCommentVO;
import com.catgal.server.service.ICommentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 游戏评论表 前端控制器
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@RestController
@RequestMapping("/comment")
@RequiredArgsConstructor
public class CommentController {

    private final ICommentService commentService;
    private final StringRedisTemplate redisTemplate;

    @GetMapping("/page")
    @Operation(summary = "分页查询游戏下的根评论列表")
    public R<GameConnectVO<CommentVO>> pageGameComment(GameQuery query){
        GameConnectVO<CommentVO> vo = commentService.pageGameComment(query);
        return R.ok(vo);
    }

    @GetMapping("/reply/{replyCommentId}")
    @Operation(summary = "查看某个评论下的回复")
    public R<PageDTO<CommentVO>> getChildComments(@PathVariable Long replyCommentId, PageQuery query) {
        PageDTO<CommentVO> page = commentService.getChildComments(replyCommentId, query);
        return R.ok(page);
    }

    @PostMapping
    @Operation(summary = "发布根评论")
    public R<Void> addComment(@Valid @RequestBody CommentAddDTO dto) {
        commentService.addComment(dto);
        return R.ok();
    }

    @PostMapping("/reply")
    @Operation(summary = "回复接口")
    public R<Void> replyComment(@Valid @RequestBody ReplyCommentDTO dto) {
        commentService.replyComment(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户自己的评论")
    public R<Void> deleteComment(@PathVariable Long id) {
        commentService.deleteComment(id);
        return R.ok();
    }



}
