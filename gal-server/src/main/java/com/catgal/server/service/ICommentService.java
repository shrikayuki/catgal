package com.catgal.server.service;


import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.CommentVO;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.server.domain.po.Comment;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

/**
 * <p>
 * 游戏评论表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface ICommentService extends IService<Comment> {

    GameConnectVO<CommentVO> pageGameComment(GameQuery query);

    void addComment(@Valid CommentAddDTO dto);

    void replyComment(@Valid ReplyCommentDTO dto);

    PageDTO<CommentVO> getChildComments(Long parentId, PageQuery query);

    void deleteComment(Long id);
}
