/*
 * 阅读说明
 * 管理员只读对比与显式修复缓存统计，汇总脏队列和数据库状态。
 * 修复属于具体业务操作，展示的缓存值、数据库值和缺失标记分别保留。
 */
package com.catgal.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.vo.admin.CounterValuesVO;
import com.catgal.server.domain.vo.admin.GameStatsConsistencyVO;
import com.catgal.server.domain.vo.admin.RedisConsistencyOverviewVO;
import com.catgal.server.domain.vo.admin.ResourceDownloadConsistencyVO;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.catgal.common.constants.LikeBizTypeConstant.*;
import static com.catgal.common.constants.RedisConstant.*;

@Service
@RequiredArgsConstructor
public class RedisConsistencyAdminService {

    private static final int ADMIN_SYNC_BATCH_SIZE = 500;

    private final StringRedisTemplate redisTemplate;
    private final IGameStatsService statsService;
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final IResourceCountService resourceCountService;

    public RedisConsistencyOverviewVO overview() {
        Map<String, Long> pending = new LinkedHashMap<>();
        pending.put("mysql:resource-count-outbox", resourceCountService.pendingCount());
        pending.put(GAME_STATS_COUNT_CHANGE_SET_KEY, setSize(GAME_STATS_COUNT_CHANGE_SET_KEY));
        pending.put(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, setSize(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY));
        putDirtySet(pending, FAVORITE_CHANGE_SET_KEY);
        putDirtySet(pending, CUTE_POINTS_CHANGE_SET_KEY);
        putDirtySet(pending, PRIZE_POOL_CHANGE_SET_KEY);
        pending.put(FOLLOW_CHANGE_SET_KEY, setSize(FOLLOW_CHANGE_SET_KEY));
        putLikeDirtySets(pending, LIKE_TYPE_COMMENT);
        putLikeDirtySets(pending, LIKE_TYPE_REVIEW);
        putLikeDirtySets(pending, LIKE_TYPE_RESOURCE);

        Map<String, String> suspicious = new LinkedHashMap<>();
        addIfExists(suspicious, COMMENT_COUNT_KEY);
        addIfExists(suspicious, RESOURCE_COUNT_KEY);
        addIfExists(suspicious, GAME_RATING_KEY);

        long total = pending.values().stream().mapToLong(Long::longValue).sum();
        return RedisConsistencyOverviewVO.builder()
                .pendingCounts(pending)
                .suspiciousLiteralKeys(suspicious)
                .totalPending(total)
                .backlogDetected(total > 0 || !suspicious.isEmpty())
                .build();
    }

    public GameStatsConsistencyVO inspectGame(Long gameId) {
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            throw BusinessException.notFound("游戏不存在");
        }

        CounterValuesVO database = fromGame(game);
        CounterValuesVO redis = readRedisGameStats(gameId);
        CounterValuesVO truth = CounterValuesVO.builder()
                .favoriteCount(countFavorites(gameId))
                .commentCount(commentMapper.countVisibleInGame(gameId))
                .reviewCount(countReviews(gameId))
                .resourceCount(countResources(gameId))
                .build();
        List<String> issues = compareGameStats(database, redis, truth);
        return GameStatsConsistencyVO.builder()
                .gameId(gameId)
                .database(database)
                .redis(redis)
                .relationTruth(truth)
                .issues(issues)
                .consistent(issues.isEmpty())
                .build();
    }

    @org.springframework.transaction.annotation.Transactional
    public GameStatsConsistencyVO repairGame(Long gameId) {
        // 与收藏写入共用游戏锁，锁定后再检查积压，防止检查通过后覆盖刚产生的收藏计数。
        if (gameMapper.selectForUpdate(gameId) == null) throw BusinessException.notFound("游戏不存在");
        if (setSize(FAVORITE_CHANGE_SET_KEY) > 0 || setSize(FAVORITE_CHANGE_SET_KEY + ":processing") > 0) {
            throw BusinessException.conflict("收藏明细仍在同步，暂不能以关系表修复 favorite_count");
        }
        GameStatsConsistencyVO inspection = inspectGame(gameId);
        CounterValuesVO db = inspection.getDatabase();
        CounterValuesVO redis = inspection.getRedis();
        CounterValuesVO truth = inspection.getRelationTruth();

        writeCounter(FAVORITE_COUNT_KEY, gameId, truth.getFavoriteCount());
        writeCounter(REVIEW_COUNT_KEY, gameId, truth.getReviewCount());
        writeCounter(RESOURCE_COUNT_KEY, gameId, truth.getResourceCount());
        writeCounter(LOOK_COUNT_KEY, gameId, max(db.getViewCount(), redis.getViewCount()));
        writeCounter(GAME_DOWNLOAD_COUNT_KEY, gameId, max(db.getDownloadCount(), redis.getDownloadCount()));
        redisTemplate.opsForSet().add(GAME_STATS_COUNT_CHANGE_SET_KEY, gameId.toString());
        // 只更新本次已锁定游戏，持锁期间不进入全局批次，避免反向获取其他游戏锁。
        Game update = new Game().setId(gameId).setFavoriteCount(truth.getFavoriteCount())
                .setCommentCount(truth.getCommentCount()).setReviewCount(truth.getReviewCount())
                .setResourceCount(truth.getResourceCount())
                .setViewCount(max(db.getViewCount(), redis.getViewCount()))
                .setDownloadCount(max(db.getDownloadCount(), redis.getDownloadCount()));
        if (gameMapper.updateById(update) != 1) throw BusinessException.internalError("游戏统计修复失败");
        return inspectGame(gameId);
    }

    public ResourceDownloadConsistencyVO inspectResource(Long resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw BusinessException.notFound("资源不存在");
        }
        Integer redisCount = readCounter(StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, resourceId));
        int databaseCount = valueOrZero(resource.getDownloadCount());
        List<String> issues = new ArrayList<>();
        if (redisCount == null) {
            issues.add("Redis 资源下载计数缺失或不是合法非负整数");
        } else if (redisCount != databaseCount) {
            issues.add("Redis 与 resource.download_count 不一致");
        }
        return ResourceDownloadConsistencyVO.builder()
                .resourceId(resourceId)
                .databaseCount(databaseCount)
                .redisCount(redisCount)
                .issues(issues)
                .consistent(issues.isEmpty())
                .build();
    }

    public ResourceDownloadConsistencyVO repairResource(Long resourceId) {
        ResourceDownloadConsistencyVO inspection = inspectResource(resourceId);
        int repaired = max(inspection.getDatabaseCount(), inspection.getRedisCount());
        redisTemplate.opsForValue().set(StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, resourceId),
                String.valueOf(repaired));
        redisTemplate.opsForSet().add(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, resourceId.toString());
        statsService.checkResourceDownLoad(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, ADMIN_SYNC_BATCH_SIZE);
        return inspectResource(resourceId);
    }

    public RedisConsistencyOverviewVO triggerSync() {
        resourceCountService.synchronizePending();
        statsService.checkGameStats(GAME_STATS_COUNT_CHANGE_SET_KEY, ADMIN_SYNC_BATCH_SIZE);
        statsService.checkResourceDownLoad(RESOURCE_DOWNLOAD_COUNT_CHANGE_SET_KEY, ADMIN_SYNC_BATCH_SIZE);
        return overview();
    }

    private List<String> compareGameStats(CounterValuesVO database,
                                          CounterValuesVO redis,
                                          CounterValuesVO truth) {
        List<String> issues = new ArrayList<>();
        compare(issues, "favorite_count", database.getFavoriteCount(), redis.getFavoriteCount(), truth.getFavoriteCount());
        // 评论数由 MySQL 同步维护，Redis 列使用空值表示不适用。
        if (!Objects.equals(database.getCommentCount(), truth.getCommentCount())) {
            issues.add("comment_count 与公开评论实时统计不一致");
        }
        compare(issues, "review_count", database.getReviewCount(), redis.getReviewCount(), truth.getReviewCount());
        compare(issues, "resource_count", database.getResourceCount(), redis.getResourceCount(), truth.getResourceCount());
        compareMonotonic(issues, "view_count", database.getViewCount(), redis.getViewCount());
        compareMonotonic(issues, "download_count", database.getDownloadCount(), redis.getDownloadCount());
        return issues;
    }

    private void compare(List<String> issues, String field, Integer database, Integer redis, Integer truth) {
        if (redis == null) {
            issues.add(field + " 的 Redis Key 缺失或值非法");
        }
        if (!Objects.equals(valueOrZero(database), valueOrZero(truth))) {
            issues.add(field + " 与关系表实时统计不一致");
        }
        if (redis != null && !Objects.equals(redis, truth)) {
            issues.add(field + " 的 Redis 值与关系表实时统计不一致");
        }
    }

    private void compareMonotonic(List<String> issues, String field, Integer database, Integer redis) {
        if (redis == null) {
            issues.add(field + " 的 Redis Key 缺失或值非法");
        } else if (!Objects.equals(valueOrZero(database), redis)) {
            issues.add(field + " 的 Redis 值尚未同步到数据库");
        }
    }

    private CounterValuesVO readRedisGameStats(Long gameId) {
        return CounterValuesVO.builder()
                .favoriteCount(readCounter(StringUtils.format(FAVORITE_COUNT_KEY, gameId)))
                .viewCount(readCounter(StringUtils.format(LOOK_COUNT_KEY, gameId)))
                .reviewCount(readCounter(StringUtils.format(REVIEW_COUNT_KEY, gameId)))
                .downloadCount(readCounter(StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, gameId)))
                .resourceCount(readCounter(StringUtils.format(RESOURCE_COUNT_KEY, gameId)))
                .build();
    }

    private CounterValuesVO fromGame(Game game) {
        return CounterValuesVO.builder()
                .favoriteCount(valueOrZero(game.getFavoriteCount()))
                .viewCount(valueOrZero(game.getViewCount()))
                .commentCount(valueOrZero(game.getCommentCount()))
                .reviewCount(valueOrZero(game.getReviewCount()))
                .downloadCount(valueOrZero(game.getDownloadCount()))
                .resourceCount(valueOrZero(game.getResourceCount()))
                .build();
    }

    private int countFavorites(Long gameId) {
        return favoriteItemMapper.selectCount(new LambdaQueryWrapper<FavoriteItem>()
                .eq(FavoriteItem::getGameId, gameId)).intValue();
    }

    private int countReviews(Long gameId) {
        return gameReviewMapper.selectCount(new LambdaQueryWrapper<GameReview>()
                .eq(GameReview::getGameId, gameId).eq(GameReview::getStatus, 1)).intValue();
    }

    private int countResources(Long gameId) {
        return resourceMapper.selectCount(new LambdaQueryWrapper<Resource>()
                .eq(Resource::getGameId, gameId).eq(Resource::getStatus, 1)).intValue();
    }

    private void writeCounter(String template, Long id, Integer value) {
        redisTemplate.opsForValue().set(StringUtils.format(template, id), String.valueOf(valueOrZero(value)));
    }

    private Integer readCounter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private long setSize(String key) {
        Long size = redisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }

    private void putDirtySet(Map<String, Long> pending, String key) {
        pending.put(key, setSize(key));
        pending.put(key + ":processing", setSize(key + ":processing"));
    }

    private void putLikeDirtySets(Map<String, Long> pending, String bizType) {
        putDirtySet(pending, StringUtils.format(LIKE_RECORD_CHANGE_SET_KEY, bizType));
        putDirtySet(pending, StringUtils.format(LIKE_COUNT_CHANGE_SET_KEY, bizType));
    }

    private void addIfExists(Map<String, String> suspicious, String key) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            DataType type = redisTemplate.type(key);
            suspicious.put(key, type == null ? "unknown" : type.code());
        }
    }

    private int max(Integer left, Integer right) {
        return Math.max(valueOrZero(left), valueOrZero(right));
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
