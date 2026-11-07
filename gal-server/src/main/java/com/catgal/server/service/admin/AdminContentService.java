package com.catgal.server.service.admin;

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
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.COMMENT_COUNT_KEY;
import static com.catgal.common.constants.RedisConstant.GAME_RATING_KEY;
import static com.catgal.common.constants.RedisConstant.GAME_STATS_COUNT_CHANGE_SET_KEY;
import static com.catgal.common.constants.RedisConstant.REVIEW_COUNT_KEY;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminContentService {

    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;
    private final GameMapper gameMapper;
    private final UserMapper userMapper;
    private final IGameStatsService gameStatsService;
    private final RedisCounterService redisCounterService;
    private final StringRedisTemplate redisTemplate;

    public PageDTO<AdminContentVO> pageComments(AdminPageQuery query) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(query.getStatus() != null, Comment::getStatus, query.getStatus())
                .eq(query.getGameId() != null, Comment::getGameId, query.getGameId())
                .eq(query.getUserId() != null, Comment::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), Comment::getContent, query.getKeyword());
        Page<Comment> page = commentMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page, enrichComments(page.getRecords()));
    }

    public AdminContentVO updateCommentStatus(Long commentId, Integer status) {
        requireContentStatus(status);
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw BusinessException.notFound("评论不存在");
        }
        Integer oldStatus = comment.getStatus();
        if (oldStatus != null && oldStatus.equals(status)) {
            return enrichComments(List.of(comment)).get(0);
        }
        int initialCount = gameStatsService.getGameCommentCount(comment.getGameId());
        int updated = commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .eq(Comment::getId, commentId)
                .eq(oldStatus != null, Comment::getStatus, oldStatus)
                .isNull(oldStatus == null, Comment::getStatus)
                .set(Comment::getStatus, status));
        if (updated != 1) {
            throw BusinessException.conflict("评论状态已被并发修改，请刷新后重试");
        }
        updateVisibleCount(COMMENT_COUNT_KEY, comment.getGameId(), initialCount, oldStatus, status);
        comment.setStatus(status);
        log.info("管理员治理评论, adminId={}, commentId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), commentId, oldStatus, status);
        return enrichComments(List.of(comment)).get(0);
    }

    public PageDTO<AdminContentVO> pageReviews(AdminPageQuery query) {
        LambdaQueryWrapper<GameReview> wrapper = new LambdaQueryWrapper<GameReview>()
                .eq(query.getStatus() != null, GameReview::getStatus, query.getStatus())
                .eq(query.getGameId() != null, GameReview::getGameId, query.getGameId())
                .eq(query.getUserId() != null, GameReview::getUserId, query.getUserId())
                .like(StringUtils.isNotBlank(query.getKeyword()), GameReview::getBriefReview, query.getKeyword());
        Page<GameReview> page = gameReviewMapper.selectPage(query.toSafePage(), wrapper);
        return PageDTO.of(page, enrichReviews(page.getRecords()));
    }

    public AdminContentVO updateReviewStatus(Long reviewId, Integer status) {
        requireContentStatus(status);
        GameReview review = gameReviewMapper.selectById(reviewId);
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
        updateVisibleCount(REVIEW_COUNT_KEY, review.getGameId(), initialCount, oldStatus, status);
        String ratingKey = StringUtils.format(GAME_RATING_KEY, review.getGameId());
        if (status == 1) {
            redisTemplate.opsForZSet().add(ratingKey, reviewId.toString(), review.getScore());
        } else {
            redisTemplate.opsForZSet().remove(ratingKey, reviewId.toString());
        }
        review.setStatus(status);
        log.info("管理员治理评价, adminId={}, reviewId={}, oldStatus={}, newStatus={}",
                UserContext.getUserId(), reviewId, oldStatus, status);
        return enrichReviews(List.of(review)).get(0);
    }

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
        vo.setGameName(names.games().get(gameId));
        vo.setUserId(userId);
        vo.setUsername(names.users().get(userId));
        return vo;
    }

    private RelatedNames relatedNames(Set<Long> gameIds, Set<Long> userIds) {
        Map<Long, String> games = toNameMap(gameMapper.selectBatchIds(gameIds), Game::getId, Game::getName);
        Map<Long, String> users = toNameMap(userMapper.selectBatchIds(userIds), User::getId, User::getUsername);
        return new RelatedNames(games, users);
    }

    private <T> Map<Long, String> toNameMap(List<T> values, Function<T, Long> id,
                                             Function<T, String> name) {
        if (CollUtils.isEmpty(values)) {
            return new HashMap<>();
        }
        return values.stream().collect(Collectors.toMap(id, name, (left, right) -> left));
    }

    private void requireContentStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.badRequest("内容状态只能是0隐藏或1正常");
        }
    }

    private record RelatedNames(Map<Long, String> games, Map<Long, String> users) {
    }
}
