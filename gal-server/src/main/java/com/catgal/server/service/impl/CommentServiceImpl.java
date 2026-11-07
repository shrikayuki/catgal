package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.CommentAddDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.CommentVO;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.LikeRecordMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.ICommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.RedisCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;
import static com.catgal.common.constants.RedisConstant.*;

/**
 * <p>
 * 游戏评论表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final IGameStatsService statsService;
    private final RedisCounterService redisCounterService;

    @Override
    public GameConnectVO<CommentVO> pageGameComment(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<CommentVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<Comment> page = lambdaQuery().eq(Comment::getGameId, gameId)
                .eq(Comment::getStatus, 1)
                .isNull(Comment::getParentId)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return vo;
        }
        List<CommentVO> vos = getVOS(records);

        vo.setPage(PageDTO.of(page, vos));
        return vo;




    }

    @Override
    public void addComment(CommentAddDTO dto) {
        Long gameId = dto.getGameId();
        if (gameId == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        requirePublishedGame(gameId);
        int initialCount = statsService.getGameCommentCount(gameId);
        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setUserId(userId);
        boolean success = save(comment);
        if (!success) {
            log.error("失败");
        }
        redisCounterService.changeAndMark(StringUtils.format(COMMENT_COUNT_KEY, gameId),
                initialCount, 1L, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
    }

    @Override
    public void replyComment(ReplyCommentDTO dto) {
        Long gameId = dto.getGameId();
        if (gameId == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        requirePublishedGame(gameId);
        Comment parent = getById(dto.getParentId());
        if (parent == null || !Objects.equals(parent.getStatus(), 1)
                || !Objects.equals(parent.getGameId(), gameId)) {
            throw BusinessException.notFound("父评论不存在、已隐藏或不属于该游戏");
        }
        if (dto.getReplyCommentId() != null) {
            Comment replyTarget = getById(dto.getReplyCommentId());
            if (replyTarget == null || !Objects.equals(replyTarget.getStatus(), 1)
                    || !Objects.equals(replyTarget.getGameId(), gameId)) {
                throw BusinessException.notFound("被回复评论不存在、已隐藏或不属于该游戏");
            }
        }
        int initialCount = statsService.getGameCommentCount(gameId);
        Comment comment = new Comment();
        log.debug("{}", comment.getContent());
        BeanUtils.copyProperties(dto, comment);
        // 打印 comment 中的值
        log.info("Comment - parentId: {}, replyCommentId: {}, content: {}",
                dto.getParentId(), dto.getReplyCommentId(), dto.getContent());
        comment.setUserId(userId);
        boolean success = save(comment);
        if (!success) {
            throw BusinessException.internalError("保存失败");
        }
        redisCounterService.changeAndMark(StringUtils.format(COMMENT_COUNT_KEY, gameId),
                initialCount, 1L, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
    }

    @Override
    public PageDTO<CommentVO> getChildComments(Long replyCommentId, PageQuery query) {
        Comment target = getById(replyCommentId);
        if (target == null || !Objects.equals(target.getStatus(), 1)) {
            throw BusinessException.notFound("评论不存在或已隐藏");
        }
        Page<Comment> page = lambdaQuery().eq(Comment::getReplyCommentId, replyCommentId)
                .eq(Comment::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 转换 VO
        List<CommentVO> vos = getVOS(records);

        return PageDTO.of(page, vos);
    }

    @Override
    @Transactional
    public void deleteComment(Long id) {
        log.info("\u001B[31mdelete comment: {}\u001B[0m", id);
        Long userId = UserContext.getUserId();
        if (id == null || userId == null) {
            log.error("评论或用户不存在");
            throw BusinessException.notFound("评论或用户不存在");
        }
        Comment comment = getById(id);
        if (comment == null) {
            throw BusinessException.notFound("评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            throw BusinessException.forbidden("不能删除他人的评论");
        }
        int initialCount = statsService.getGameCommentCount(comment.getGameId());
        int needDelCount = 1;
        List<Comment> list = null;
        if (comment.getParentId() == null) {
            //根评论 需要同时删除子评论
            list = lambdaQuery().eq(Comment::getParentId, id).eq(Comment::getStatus, 1).list();
            if (CollUtils.isNotEmpty(list)) {
                if (removeBatchByIds(list)) {
                    needDelCount += list.size();
                }
            }

        }
        boolean del = removeById(comment);
        if (!del) {
            log.error("删除失败");
        }
        Long gameId = comment.getGameId();
        redisCounterService.changeAndMark(StringUtils.format(COMMENT_COUNT_KEY, gameId),
                initialCount, -needDelCount, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
        likeService.clearLikeCache(LIKE_TYPE_COMMENT, id);
        if (CollUtils.isNotEmpty(list)) {
            for (Comment comm : list) {
                likeService.clearLikeCache(LIKE_TYPE_COMMENT, comm.getId());
            }
        }

    }

    private List<CommentVO> getVOS(List<Comment> records) {
        Set<Long> userIds = records.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<CommentVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();
        if (userId != null && CollUtils.isNotEmpty(records)) {
            List<Long> commentIds = records.stream().map(Comment::getId).collect(Collectors.toList());
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_COMMENT, commentIds);
            likeCounts = likeService.getLikeCounts(commentIds, LIKE_TYPE_COMMENT);
        }
        for (Comment c : records) {
            CommentVO commentVO = BeanUtils.copyProperties(c, CommentVO.class);
            // 设置用户信息
            User user = userMap == null ? null : userMap.get(c.getUserId());
            if (user != null) {
                commentVO.setUsername(user.getUsername());
                commentVO.setAvatarUrl(user.getAvatarUrl());
            }

            // 设置是否为当前用户评论
            if (commentVO != null) {
                commentVO.setIsMyComment(c.getUserId().equals(userId));
                //设置点赞状态
                commentVO.setIsLike(likedMap.getOrDefault(c.getId(), false));
                commentVO.setLikeCount(likeCounts.getOrDefault(c.getId(), 0L).intValue());
            }
            vos.add(commentVO);

        }
        return vos;
    }

    private void requirePublishedGame(Long gameId) {
        Game game = gameService.getById(gameId);
        if (game == null || !Objects.equals(game.getStatus(), 1)) {
            throw BusinessException.conflict("只能在已发布游戏下发表评论");
        }
    }
}
