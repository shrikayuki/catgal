/*
 * 阅读说明
 * 游戏评价与评分聚合，评价新增、删除及治理统一先锁游戏。
 * 同事务重算可见评价平均分，公开计数通过 Redis 脏任务同步。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.*;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IGameReviewService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.RedisCounterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_COMMENT;
import static com.catgal.common.constants.LikeBizTypeConstant.LIKE_TYPE_REVIEW;
import static com.catgal.common.constants.RedisConstant.*;

/**
 * <p>
 * 游戏评价表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
public class GameReviewServiceImpl extends ServiceImpl<GameReviewMapper, GameReview> implements IGameReviewService {

    private final UserMapper userMapper;
    private final IGameService gameService;
    private final ILikeRecordService likeService;
    private final GameMapper gameMapper;
    private final IGameStatsService statsService;
    private final RedisCounterService redisCounterService;

    @Override
    // 只分页展示正常评价，评分、剧透和游玩进度字段与作者展示数据一起返回。
    public GameConnectVO<GameReviewVO> pageGameReview(GameQuery query) {
        Long gameId = query.getGameId();
        if (query.getGameId() == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        GameVO gameInfo = gameService.getGameSimpleById(gameId);
        // 先转换基本对象
        GameConnectVO<GameReviewVO> vo = new GameConnectVO<>();
        BeanUtils.copyProperties(gameInfo, vo);

        Page<GameReview> page = lambdaQuery().eq(GameReview::getGameId, gameId)
                .eq(GameReview::getStatus, 1)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<GameReview> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return vo;
        }
        List<GameReviewVO> vos = getVOS(records);

        vo.setPage(PageDTO.of(page, vos));
        return vo;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 事务内先锁游戏再保存评价，随后按当前可见评价重新聚合平均分和数量。
    public void addGameReview(ReviewAddDTO dto) {
        Long userId = UserContext.getUserId();
        Long gameId = dto.getGameId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        if (gameId == null) {
            throw BusinessException.badRequest("游戏Id不能为空");
        }
        // All rating writers lock the game before changing its reviews.
        Game game = gameMapper.selectForUpdate(gameId);
        if (game == null || !Objects.equals(game.getStatus(), 1)) {
            throw BusinessException.conflict("只能评价已发布游戏");
        }
        int initialCount = statsService.getGameReviewCount(gameId);
        GameReview gameReview = BeanUtils.copyProperties(dto, GameReview.class);

        gameReview.setUserId(userId);
        gameReview.setStatus(1);
        boolean success = save(gameReview);
        if (!success) {
            throw BusinessException.internalError("评价保存失败");
        }
        gameMapper.refreshRating(gameId);
        redisCounterService.changeAndMark(StringUtils.format(REVIEW_COUNT_KEY, gameId),
                initialCount, 1L, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);

    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    // 本人校验后按游戏、评价的顺序加锁，删除与评分重算在同一数据库事务提交。
    public void deleteGameReview(Long id) {
        Long userId = UserContext.getUserId();
        GameReview review = baseMapper.selectById(id);
        if (review == null) {
            log.error("评价不存在");
            throw BusinessException.notFound("该评价不存在或是被删除");
        }
        if (!review.getUserId().equals(userId)) {
            throw BusinessException.forbidden("只能删除自己的评价");
        }
        Long gameId = review.getGameId();
        if (gameMapper.selectForUpdate(gameId) == null) {
            throw BusinessException.notFound("游戏不存在");
        }
        review = baseMapper.selectForUpdate(id);
        if (review == null) {
            throw BusinessException.notFound("评价不存在或是被删除");
        }
        int initialCount = statsService.getGameReviewCount(gameId);
        boolean success = removeById(review);
        if (!success) {
            throw BusinessException.conflict("评价已发生变化，请刷新后重试");
        }
        gameMapper.refreshRating(gameId);
        if (Objects.equals(review.getStatus(), 1)) {
            redisCounterService.changeAndMark(StringUtils.format(REVIEW_COUNT_KEY, gameId),
                    initialCount, -1L, GAME_STATS_COUNT_CHANGE_SET_KEY, gameId);
        }
        likeService.clearLikeCache(LIKE_TYPE_REVIEW, id);
    }

    // 批量查询作者及点赞状态，将评价实体复制为可展示的响应视图。
    private List<GameReviewVO> getVOS(List<GameReview> records) {
        Set<Long> userIds = records.stream().map(GameReview::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = null;
        if (CollUtils.isNotEmpty(userIds)) {
            List<User> users = userMapper.selectBatchIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        }
        List<GameReviewVO> vos = new ArrayList<>(records.size());
        Long userId = UserContext.getUserId();
        Map<Long, Boolean> likedMap = new HashMap<>();
        Map<Long, Long> likeCounts = new HashMap<>();
        if (userId != null && CollUtils.isNotEmpty(records)) {
            List<Long> ReviewIds = records.stream().map(GameReview::getId).collect(Collectors.toList());
            likedMap = likeService.batchIsLiked(userId, LIKE_TYPE_REVIEW, ReviewIds);
            likeCounts = likeService.getLikeCounts(ReviewIds, LIKE_TYPE_REVIEW);
        }


        for (GameReview r : records) {
            GameReviewVO vo = BeanUtils.copyBean(r, GameReviewVO.class);

            // 设置用户信息
            User user = userMap == null ? null : userMap.get(r.getUserId());
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setAvatarUrl(com.catgal.server.storage.FileAccessPaths.avatar(user));
            }

            // 设置是否为当前用户评论
            if (vo != null) {
                vo.setIsMyReview(r.getUserId().equals(userId));
                //  设置点赞状态
                vo.setIsLike(likedMap.getOrDefault(r.getId(), false));
                vo.setLikeCount(likeCounts.getOrDefault(r.getId(), 0L).intValue());
            }
            vos.add(vo);

        }
        return vos;
    }
}
