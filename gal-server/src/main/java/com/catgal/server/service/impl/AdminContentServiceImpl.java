/*
 * 阅读说明
 * AdminContentServiceImpl 负责管理端对应业务的查询与状态维护。
 * Controller 处理参数绑定，事务、校验、缓存更新和结果装配在本服务完成。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.query.admin.AdminPageQuery;
import com.catgal.server.domain.vo.admin.AdminContentVO;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IAdminContentService;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import com.catgal.server.storage.FileAccessPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.GAME_STATS_COUNT_CHANGE_SET_KEY;
import static com.catgal.common.constants.RedisConstant.REVIEW_COUNT_KEY;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminContentServiceImpl implements IAdminContentService {

    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;
    private final GameMapper gameMapper;
    private final UserMapper userMapper;
    private final IGameStatsService gameStatsService;
    private final RedisCounterService redisCounterService;
    private final IAdminOperationLogService auditService;

    @Override
    public PageDTO<AdminContentVO> pageComments(AdminPageQuery query) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(query.getStatus() != null, Comment::getStatus, query.getStatus())
                .eq(query.getGameId() != null, Comment::getGameId, query.getGameId())
                .eq(query.getUserId() != null, Comment::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Comment::getContent, query.getKeyword());
        Page<Comment> page = commentMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page, enrichComments(page.getRecords()));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 游戏行锁串行化同一游戏的评论变更，状态、计数和审计一起提交。
    public AdminContentVO updateCommentStatus(Long commentId, Integer status) {
        requireContentStatus(status);
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw BusinessException.notFound("评论不存在");
        }
        Game game = gameMapper.selectForUpdate(comment.getGameId());
        comment = commentMapper.selectForUpdate(commentId);
        if (comment == null) {
            throw BusinessException.notFound("评论不存在");
        }
        Integer oldStatus = comment.getStatus();
        if (oldStatus != null && oldStatus.equals(status)) {
            return enrichComments(List.of(comment)).get(0);
        }
        int updated = commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .eq(Comment::getId, commentId)
                .eq(oldStatus != null, Comment::getStatus, oldStatus)
                .isNull(oldStatus == null, Comment::getStatus)
                .set(Comment::getStatus, status));
        if (updated != 1) {
            throw BusinessException.conflict("评论状态已被并发修改，请刷新后重试");
        }
        if (game != null) gameMapper.refreshCommentCount(game.getId());
        auditService.appendContentStatusChange(UserContext.getUserId(), "comment", commentId,
                comment.getGameId(), oldStatus, status);
        comment.setStatus(status);
        log.info("管理员治理评论, adminId={}, commentId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), commentId, oldStatus, status);
        return enrichComments(List.of(comment)).get(0);
    }

    @Override
    public PageDTO<AdminContentVO> pageReviews(AdminPageQuery query) {
        LambdaQueryWrapper<GameReview> wrapper = new LambdaQueryWrapper<GameReview>()
                .eq(query.getStatus() != null, GameReview::getStatus, query.getStatus())
                .eq(query.getGameId() != null, GameReview::getGameId, query.getGameId())
                .eq(query.getUserId() != null, GameReview::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), GameReview::getBriefReview, query.getKeyword());
        Page<GameReview> page = gameReviewMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page, enrichReviews(page.getRecords()));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 按游戏到评价的顺序加锁，变更可见状态后重算评分与评价计数。
    public AdminContentVO updateReviewStatus(Long reviewId, Integer status) {
        requireContentStatus(status);
        GameReview review = gameReviewMapper.selectById(reviewId);
        if (review == null) {
            throw BusinessException.notFound("评价不存在");
        }
        if (gameMapper.selectForUpdate(review.getGameId()) == null) {
            throw BusinessException.notFound("游戏不存在");
        }
        review = gameReviewMapper.selectForUpdate(reviewId);
        if (review == null) {
            throw BusinessException.notFound("评价不存在");
        }
        Integer oldStatus = review.getStatus();
        if (oldStatus != null && oldStatus.equals(status)) {
            return enrichReviews(List.of(review)).get(0);
        }
        int initialCount = gameStatsService.getGameReviewCount(review.getGameId());
        int updated = gameReviewMapper.update(null, Wrappers.<GameReview>lambdaUpdate()
                .eq(GameReview::getId, reviewId)
                .eq(oldStatus != null, GameReview::getStatus, oldStatus)
                .isNull(oldStatus == null, GameReview::getStatus)
                .set(GameReview::getStatus, status));
        if (updated != 1) {
            throw BusinessException.conflict("评价状态已被并发修改，请刷新后重试");
        }
        gameMapper.refreshRating(review.getGameId());
        auditService.appendContentStatusChange(UserContext.getUserId(), "review", reviewId,
                review.getGameId(), oldStatus, status);
        review.setStatus(status);
        AdminContentVO result = enrichReviews(List.of(review)).get(0);
        // 审计或查询装配失败时数据库会回滚，评价的 Redis 增量也尚未写出。
        updateVisibleCount(REVIEW_COUNT_KEY, review.getGameId(), initialCount, oldStatus, status);
        log.info("管理员治理评价, adminId={}, reviewId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), reviewId, oldStatus, status);
        return result;
    }

    // 只计算公开状态切换产生的差额，重复提交相同状态不会再次改变计数。
    private void updateVisibleCount(String keyTemplate, Long gameId, int initialCount,
                                    Integer oldStatus, Integer newStatus) {
        int delta = (newStatus == 1 ? 1 : 0) - (oldStatus != null && oldStatus == 1 ? 1 : 0);
        if (delta != 0) {
            redisCounterService.changeAndMark(StringUtils.format(keyTemplate, gameId), initialCount,
                    delta, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
        }
    }

    private List<AdminContentVO> enrichComments(List<Comment> comments) {
        if (CollUtils.isEmpty(comments)) {
            return List.of();
        }
        RelatedNames names = relatedNames(comments.stream().map(Comment::getGameId).collect(Collectors.toSet()),
                comments.stream().map(Comment::getUserId).collect(Collectors.toSet()));
        return comments.stream().map(comment -> {
            AdminContentVO vo = base("comment", comment.getId(), comment.getGameId(), comment.getUserId(), names);
            vo.setContent(comment.getContent());
            vo.setLikeCount(comment.getLikeCount());
            vo.setStatus(comment.getStatus());
            vo.setCreateTime(comment.getCreateTime());
            vo.setUpdateTime(comment.getUpdateTime());
            return vo;
        }).toList();
    }

    private List<AdminContentVO> enrichReviews(List<GameReview> reviews) {
        if (CollUtils.isEmpty(reviews)) {
            return List.of();
        }
        RelatedNames names = relatedNames(reviews.stream().map(GameReview::getGameId).collect(Collectors.toSet()),
                reviews.stream().map(GameReview::getUserId).collect(Collectors.toSet()));
        return reviews.stream().map(review -> {
            AdminContentVO vo = base("review", review.getId(), review.getGameId(), review.getUserId(), names);
            vo.setContent(review.getBriefReview());
            vo.setScore(review.getScore());
            vo.setSpoilerLevel(review.getSpoilerLevel());
            vo.setLikeCount(review.getLikeCount());
            vo.setStatus(review.getStatus());
            vo.setCreateTime(review.getCreateTime());
            vo.setUpdateTime(review.getUpdateTime());
            return vo;
        }).toList();
    }

    private AdminContentVO base(String type, Long id, Long gameId, Long userId, RelatedNames names) {
        AdminContentVO vo = new AdminContentVO();
        vo.setType(type);
        vo.setId(id);
        vo.setGameId(gameId);
        Game game = names.games().get(gameId);
        vo.setGameName(game == null ? null : game.getName());
        vo.setUserId(userId);
        User user = names.users().get(userId);
        vo.setUsername(user == null ? null : user.getUsername());
        vo.setAvatarUrl(user == null ? null : FileAccessPaths.avatar(user));
        return vo;
    }

    private RelatedNames relatedNames(Set<Long> gameIds, Set<Long> userIds) {
        // 头像与用户名共用现有批量查询，列表中每条记录无需单独查用户。
        return new RelatedNames(indexById(gameMapper.selectBatchIds(gameIds), Game::getId),
                indexById(userMapper.selectBatchIds(userIds), User::getId));
    }

    private <T> Map<Long, T> indexById(List<T> values, Function<T, Long> id) {
        if (CollUtils.isEmpty(values)) {
            return Collections.emptyMap();
        }
        return values.stream().collect(Collectors.toMap(id, Function.identity(), (left, right) -> left));
    }

    private void requireContentStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.badRequest("内容状态只能是0隐藏或1正常");
        }
    }

    private record RelatedNames(Map<Long, Game> games, Map<Long, User> users) {
    }
}
