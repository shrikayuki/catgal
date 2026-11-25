/*
 * 阅读说明
 * 分页查询游戏下的根评论列表、查看某个评论下的回复、发布根评论的 HTTP 入口。
 * 路由前缀 /comment，请求绑定后委托 ICommentService、StringRedisTemplate。
 * LoginRequired 标记的入口要求登录，上下文用户身份来自服务端认证。
 */
package com.catgal.server.controller;


import com.catgal.common.domain.R;
import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.common.domain.vo.CommentVO;

import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.UserCommentVO;
import com.catgal.server.service.ICommentService;
import com.catgal.server.mvc.annotation.LoginRequired;
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
    public R<GameConnectVO<CommentVO>> pageGameComment(@Valid GameQuery query,
                                                       @RequestParam(defaultValue = "false") boolean withReplies) {
        GameConnectVO<CommentVO> vo = commentService.pageGameComment(query, withReplies);
        return R.ok(vo);
    }

    @GetMapping("/reply/{replyCommentId}")
    @Operation(summary = "查看某个评论下的回复")
    public R<PageDTO<CommentVO>> getChildComments(@PathVariable Long replyCommentId, PageQuery query) {
        PageDTO<CommentVO> page = commentService.getChildComments(replyCommentId, query);
        return R.ok(page);
    }

    @GetMapping("/thread/{rootId}/replies")
    @Operation(summary = "分页查看一条主评论下的可见回复")
    public R<PageDTO<CommentVO>> getThreadReplies(@PathVariable Long rootId, @Valid PageQuery query) {
        return R.ok(commentService.getThreadReplies(rootId, query));
    }

    @GetMapping("/thread/{rootId}/replies/scroll")
    @Operation(summary = "滚动读取主评论下的可见回复", description = "固定按回复ID倒序，首次省略cursor")
    public R<ScrollPageDTO<CommentVO>> scrollThreadReplies(@PathVariable Long rootId, @Valid ScrollPageQuery query) {
        return R.ok(commentService.scrollThreadReplies(rootId, query));
    }

    @PostMapping
    @LoginRequired
    @Operation(summary = "发布根评论")
    public R<Void> addComment(@Valid @RequestBody CommentAddDTO dto) {
        commentService.addComment(dto);
        return R.ok();
    }

    @PostMapping("/reply")
    @LoginRequired
    @Operation(summary = "回复接口")
    public R<Void> replyComment(@Valid @RequestBody ReplyCommentDTO dto) {
        commentService.replyComment(dto);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @LoginRequired
    @Operation(summary = "删除用户自己的评论")
    public R<Void> deleteComment(@PathVariable Long id) {
        commentService.deleteComment(id);
        return R.ok();
    }



}
