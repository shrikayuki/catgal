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
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.LikeRecordMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.ICommentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.ILikeRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;
import static com.catgal.common.constants.RedisConstant.COMMENT_COUNT_KEY;

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
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final RedisTemplate<Object, Object> redisTemplate;

    @Override
    public GameConnectVO<CommentVO> pageGameComment(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw new RuntimeException("游戏Id不能为空");
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
        if (dto.getGameId() == null) {
            throw new RuntimeException("游戏Id不能为空");
        }
        Long userId = UserContext.getUserId();
        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setUserId(userId);
        boolean success = save(comment);
        if (!success) {
            throw new RuntimeException("保存失败");
        }
        redisTemplate.opsForValue().increment(COMMENT_COUNT_KEY);
    }

    @Override
    public void replyComment(ReplyCommentDTO dto) {
        if (dto.getGameId() == null) {
            throw new RuntimeException("游戏Id不能为空");
        }
        Comment comment = new Comment();
        BeanUtils.copyProperties(dto, comment);
        comment.setUserId(UserContext.getUserId());
        boolean success = save(comment);
        if (!success) {
            throw new RuntimeException("保存失败");
        }
        redisTemplate.opsForValue().increment(COMMENT_COUNT_KEY);
    }

    @Override
    public PageDTO<CommentVO> getChildComments(Long parentId, PageQuery query) {
        // 查询子评论（parent_id = parentId）
        Page<Comment> page = lambdaQuery().eq(Comment::getParentId, parentId)
                .eq(Comment::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Comment> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 转换 VO
        List<CommentVO> vos = getVOS(records);;


        return PageDTO.of(page, vos);
    }

    @Override
    public void deleteComment(Long id) {
        Long userId = UserContext.getUserId();
        if (id == null || userId == null) {
            log.error("评论或用户不存在");
            throw new RuntimeException("评论或用户不存在");
        }
        Comment comment = getById(id);
        if (comment == null) {
            throw new RuntimeException("评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            throw new RuntimeException("不能删除他人的评论");
        }
        boolean del = removeById(comment);
        if (!del) {
            log.error("删除失败");
        }
        redisTemplate.opsForValue().increment(COMMENT_COUNT_KEY, -1);
        likeService.clearLikeCache(LIKE_TYPE_COMMENT, id);
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
            CommentVO commentVO = BeanUtils.copyBean(c, CommentVO.class);

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
}
