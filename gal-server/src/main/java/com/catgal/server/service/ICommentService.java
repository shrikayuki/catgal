/*
 * 阅读说明
 * 游戏评论的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;


import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.dto.ScrollPageDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.query.ScrollPageQuery;
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

    /** 先核验所属游戏，再分页读取可见评论并批量装配作者和互动状态。 */
    GameConnectVO<CommentVO> pageGameComment(GameQuery query, boolean withReplies);

    /** 先锁游戏，登录用户绑定为作者，评论与公开计数在同一事务提交。 */
    void addComment(@Valid CommentAddDTO dto);

    /** 按游戏、主评论、回复目标的顺序加锁，回复与公开计数在同一事务提交。 */
    void replyComment(@Valid ReplyCommentDTO dto);

    /** 根据 replyCommentId 查询直接回复目标的对话，并校验目标和所属游戏仍可见。 */
    PageDTO<CommentVO> getChildComments(Long replyCommentId, PageQuery query);

    /** 按根评论分页读取可见回复，同一楼的所有层级按时间平铺。 */
    PageDTO<CommentVO> getThreadReplies(Long rootId, PageQuery query);

    /** 按回复 ID 倒序读取同一楼中的可见回复，游标只表示下一页的 ID 边界。 */
    ScrollPageDTO<CommentVO> scrollThreadReplies(Long rootId, ScrollPageQuery query);

    /** 按游戏、主评论、目标的顺序加锁，删除及计数刷新同事务完成。 */
    void deleteComment(Long id);
}
