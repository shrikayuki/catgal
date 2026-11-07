package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.service.IGameStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

@Service
@Slf4j
@RequiredArgsConstructor
public class GameStatsService implements IGameStatsService {

    private static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(2);
    private static final int MAX_BATCHES_PER_RUN = 10;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            load("lua/common/release-lock.lua", Long.class);
    private static final DefaultRedisScript<Long> ACK_IF_UNCHANGED_SCRIPT =
            load("lua/game-stats/ack-if-unchanged.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;

    @Override
    public Integer getGameReviewCount(Long id) {
        return getCounterOrFallback(StringUtils.format(REVIEW_COUNT_KEY, id), () -> gameReviewMapper.selectCount(
                new LambdaQueryWrapper<GameReview>()
                        .eq(GameReview::getGameId, id)
                        .eq(GameReview::getStatus, 1)
        ).intValue());
    }

    @Override
    public Integer getGameLookCount(Long id) {
        return getGameCounter(id, LOOK_COUNT_KEY, Game::getViewCount);
    }

    @Override
    public Integer getGameFavoriteCount(Long gameId) {
        return getGameCounter(gameId, FAVORITE_COUNT_KEY, Game::getFavoriteCount);
    }

    @Override
    public Integer getGameResourceCount(Long gameId) {
        return getCounterOrFallback(StringUtils.format(RESOURCE_COUNT_KEY, gameId), () -> resourceMapper.selectCount(
                new LambdaQueryWrapper<Resource>()
                        .eq(Resource::getGameId, gameId)
                        .eq(Resource::getStatus, 1)
        ).intValue());
    }

    @Override
    public Integer getGameDownLoadCount(Long id) {
        return getGameCounter(id, GAME_DOWNLOAD_COUNT_KEY, Game::getDownloadCount);
    }

    @Override
    public Integer getGameCommentCount(Long id) {
        return getCounterOrFallback(StringUtils.format(COMMENT_COUNT_KEY, id), () -> commentMapper.selectCount(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getGameId, id)
                        .eq(Comment::getStatus, 1)
        ).intValue());
    }

    @Override
    public BigDecimal getRating(Long id) {
        String key = StringUtils.format(GAME_RATING_KEY, id);
        Long count = redisTemplate.opsForZSet().size(key);
        if (count == null || count == 0) {
            Game game = gameMapper.selectById(id);
            return game == null || game.getRating() == null ? BigDecimal.ZERO : game.getRating();
        }

        double sum = 0.0;
        int batchSize = 1000;
        for (int offset = 0; offset < count; offset += batchSize) {
            Set<ZSetOperations.TypedTuple<String>> batch =
                    redisTemplate.opsForZSet().rangeWithScores(key, offset, offset + batchSize - 1);
            if (batch != null) {
                for (ZSetOperations.TypedTuple<String> tuple : batch) {
                    if (tuple.getScore() != null) {
                        sum += tuple.getScore();
                    }
                }
            }
        }
        return BigDecimal.valueOf(sum / count).setScale(1, RoundingMode.HALF_UP);
    }

    @Override
    public Integer getResourceDownLoadCount(Long resourceId) {
        return getCounterOrFallback(StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, resourceId), () -> {
            Resource resource = resourceMapper.selectById(resourceId);
            return resource == null || resource.getDownloadCount() == null ? 0 : resource.getDownloadCount();
        });
    }

    @Override
    public void checkGameStats(String dirtySetKey, Integer batchSize) {
        withSyncLock(GAME_STATS_SYNC_LOCK_KEY, () -> {
            for (int i = 0; i < MAX_BATCHES_PER_RUN && syncGameStatsBatch(dirtySetKey, batchSize); i++) {
                // 有数据时继续受限排空，避免单次任务无限占用线程。
            }
        });
    }

    @Override
    public void checkResourceDownLoad(String dirtySetKey, Integer batchSize) {
        withSyncLock(RESOURCE_DOWNLOAD_SYNC_LOCK_KEY, () -> {
            for (int i = 0; i < MAX_BATCHES_PER_RUN && syncResourceDownloadBatch(dirtySetKey, batchSize); i++) {
                // 最多处理固定批次，剩余积压交给下次调度。
            }
        });
    }

    private boolean syncGameStatsBatch(String dirtySetKey, int batchSize) {
        Set<String> members = redisTemplate.opsForSet().distinctRandomMembers(dirtySetKey, batchSize);
        if (CollUtils.isEmpty(members)) {
            return false;
        }
        List<Long> ids = parseIdsAndDiscardInvalid(dirtySetKey, members);
        if (ids.isEmpty()) {
            return false;
        }
        Map<Long, Game> dbGames = toMap(gameMapper.selectBatchIds(ids), Game::getId);
        List<Game> updates = new ArrayList<>();
        Map<Long, CounterSnapshot> snapshots = new HashMap<>();

        for (Long id : ids) {
            Game dbGame = dbGames.get(id);
            if (dbGame == null) {
                redisTemplate.opsForSet().remove(dirtySetKey, id.toString());
                log.warn("移除不存在游戏的统计脏标记, gameId={}", id);
                continue;
            }
            CounterSnapshot snapshot = gameSnapshot(id, dbGame);
            snapshots.put(id, snapshot);
            updates.add(new Game()
                    .setId(id)
                    .setFavoriteCount(snapshot.favorite())
                    .setViewCount(snapshot.view())
                    .setCommentCount(snapshot.comment())
                    .setReviewCount(snapshot.review())
                    .setDownloadCount(snapshot.download())
                    .setResourceCount(snapshot.resource()));
        }

        if (updates.isEmpty()) {
            return false;
        }
        int updated = gameMapper.batchUpdateGameStats(updates);
        snapshots.forEach((id, snapshot) -> acknowledgeGameIfUnchanged(dirtySetKey, id, snapshot));
        log.info("游戏统计同步完成, requested={}, updated={}, pending={}",
                updates.size(), updated, safeSetSize(dirtySetKey));
        return true;
    }

    private boolean syncResourceDownloadBatch(String dirtySetKey, int batchSize) {
        Set<String> members = redisTemplate.opsForSet().distinctRandomMembers(dirtySetKey, batchSize);
        if (CollUtils.isEmpty(members)) {
            return false;
        }
        List<Long> ids = parseIdsAndDiscardInvalid(dirtySetKey, members);
        Map<Long, Resource> dbResources = toMap(resourceMapper.selectBatchIds(ids), Resource::getId);
        List<Resource> updates = new ArrayList<>();
        Map<Long, Integer> snapshots = new HashMap<>();

        for (Long id : ids) {
            Resource resource = dbResources.get(id);
            if (resource == null) {
                redisTemplate.opsForSet().remove(dirtySetKey, id.toString());
                log.warn("移除不存在资源的下载统计脏标记, resourceId={}", id);
                continue;
            }
            String counterKey = StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, id);
            int value = resolveCounter(counterKey, resource.getDownloadCount());
            snapshots.put(id, value);
            updates.add(new Resource().setId(id).setDownloadCount(value));
        }

        if (updates.isEmpty()) {
            return false;
        }
        int updated = resourceMapper.batchUpdateResourceStats(updates);
        snapshots.forEach((id, value) -> acknowledgeIfUnchanged(
                List.of(dirtySetKey, StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, id)),
                List.of(id.toString(), String.valueOf(value))
        ));
        log.info("资源下载统计同步完成, requested={}, updated={}, pending={}",
                updates.size(), updated, safeSetSize(dirtySetKey));
        return true;
    }

    private CounterSnapshot gameSnapshot(Long id, Game dbGame) {
        return new CounterSnapshot(
                resolveCounter(StringUtils.format(FAVORITE_COUNT_KEY, id), dbGame.getFavoriteCount()),
                resolveCounter(StringUtils.format(LOOK_COUNT_KEY, id), dbGame.getViewCount()),
                resolveCounter(StringUtils.format(COMMENT_COUNT_KEY, id), dbGame.getCommentCount()),
                resolveCounter(StringUtils.format(REVIEW_COUNT_KEY, id), dbGame.getReviewCount()),
                resolveCounter(StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, id), dbGame.getDownloadCount()),
                resolveCounter(StringUtils.format(RESOURCE_COUNT_KEY, id), dbGame.getResourceCount())
        );
    }

    private void acknowledgeGameIfUnchanged(String dirtySetKey, Long id, CounterSnapshot snapshot) {
        acknowledgeIfUnchanged(
                List.of(
                        dirtySetKey,
                        StringUtils.format(FAVORITE_COUNT_KEY, id),
                        StringUtils.format(LOOK_COUNT_KEY, id),
                        StringUtils.format(COMMENT_COUNT_KEY, id),
                        StringUtils.format(REVIEW_COUNT_KEY, id),
                        StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, id),
                        StringUtils.format(RESOURCE_COUNT_KEY, id)
                ),
                List.of(
                        id.toString(),
                        String.valueOf(snapshot.favorite()),
                        String.valueOf(snapshot.view()),
                        String.valueOf(snapshot.comment()),
                        String.valueOf(snapshot.review()),
                        String.valueOf(snapshot.download()),
                        String.valueOf(snapshot.resource())
                )
        );
    }

    private void acknowledgeIfUnchanged(List<String> keys, List<String> arguments) {
        redisTemplate.execute(ACK_IF_UNCHANGED_SCRIPT, keys,
                (Object[]) arguments.toArray(String[]::new));
    }

    private int getGameCounter(Long id, String keyTemplate, Function<Game, Integer> getter) {
        return getCounterOrFallback(StringUtils.format(keyTemplate, id), () -> {
            Game game = gameMapper.selectById(id);
            Integer value = game == null ? null : getter.apply(game);
            return value == null ? 0 : value;
        });
    }

    private int getCounterOrFallback(String key, CounterFallback fallback) {
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            return parseNonNegativeCounter(key, value);
        }
        int fallbackValue = Math.max(0, fallback.get());
        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(fallbackValue));
        if (Boolean.TRUE.equals(initialized)) {
            return fallbackValue;
        }
        return parseNonNegativeCounter(key, redisTemplate.opsForValue().get(key));
    }

    private int resolveCounter(String key, Integer fallback) {
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            return parseNonNegativeCounter(key, value);
        }
        int fallbackValue = fallback == null ? 0 : Math.max(0, fallback);
        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(fallbackValue));
        if (Boolean.TRUE.equals(initialized)) {
            return fallbackValue;
        }
        return parseNonNegativeCounter(key, redisTemplate.opsForValue().get(key));
    }

    private int parseNonNegativeCounter(String key, String value) {
        if (value == null) {
            throw new IllegalStateException("Redis 计数器读取失败: " + key);
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis 计数器不是合法整数: " + key, exception);
        }
    }

    private List<Long> parseIdsAndDiscardInvalid(String dirtySetKey, Collection<String> members) {
        List<Long> ids = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                ids.add(Long.parseLong(member));
            } catch (NumberFormatException exception) {
                redisTemplate.opsForSet().remove(dirtySetKey, member);
                log.error("移除非法统计脏标记, key={}, member={}", dirtySetKey, member);
            }
        }
        return ids;
    }

    private <T> Map<Long, T> toMap(List<T> values, Function<T, Long> idGetter) {
        Map<Long, T> result = new HashMap<>();
        for (T value : values) {
            result.put(idGetter.apply(value), value);
        }
        return result;
    }

    private void withSyncLock(String lockKey, Runnable action) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, SYNC_LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("统计同步任务已有实例执行, lockKey={}", lockKey);
            return;
        }
        try {
            action.run();
        } finally {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }

    private long safeSetSize(String key) {
        Long size = redisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }

    @FunctionalInterface
    private interface CounterFallback {
        int get();
    }

    private record CounterSnapshot(int favorite, int view, int comment,
                                   int review, int download, int resource) {
    }
}
