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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class GameStatsService implements IGameStatsService{

    private final StringRedisTemplate redisTemplate;
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;
    private final CommentMapper commentMapper;
    private final GameReviewMapper gameReviewMapper;

    @Override
    public Integer getGameReviewCount(Long id){
        String key = StringUtils.format(REVIEW_COUNT_KEY,id);
        String value = redisTemplate.opsForValue().get(key);
        if(value == null){
            return gameReviewMapper.selectCount(
                    new LambdaQueryWrapper<GameReview>()
                            .eq(GameReview::getGameId, id)
                            .eq(GameReview::getStatus, 1)
            ).intValue();

        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameLookCount(Long id) {
        String key = StringUtils.format(LOOK_COUNT_KEY, id);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameFavoriteCount(Long gameId) {
        String key = StringUtils.format(FAVORITE_COUNT_KEY, gameId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return -1;
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameResourceCount(Long gameId) {
        String key = StringUtils.format(RESOURCE_COUNT_KEY, gameId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            // 缓存未命中，从数据库统计资源数量

            return resourceMapper.selectCount(
                    new LambdaQueryWrapper<Resource>()
                            .eq(Resource::getGameId, gameId)
                            .eq(Resource::getStatus, 1)
            ).intValue();
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameDownLoadCount(Long id) {
        String key = StringUtils.format(GAME_DOWNLOAD_COUNT_KEY, id);
        String countStr = redisTemplate.opsForValue().get(key);
        if (countStr == null) {
            return -1;
        }
        return Integer.parseInt(countStr);
    }

    @Override
    public Integer getGameCommentCount(Long id) {
        String key = StringUtils.format(COMMENT_COUNT_KEY, id);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            // 缓存未命中，从数据库统计资源数量
            Integer count = commentMapper.selectCount(
                    new LambdaQueryWrapper<Comment>()
                            .eq(Comment::getGameId, id)
                            .eq(Comment::getStatus, 1)
            ).intValue();

            return count;
        }
        return Integer.parseInt(value);
    }

    @Override
    public BigDecimal getRating(Long id) {
        String key = String.format(GAME_RATING_KEY, id);

        Long count = redisTemplate.opsForZSet().size(key);
        if (count == null || count == 0) {
            return BigDecimal.ZERO;
        }

        double sum = 0.0;
        int batchSize = 1000;
        int offset = 0;

        while (offset < count) {
            Set<ZSetOperations.TypedTuple<String>> batch =
                    redisTemplate.opsForZSet().rangeWithScores(key, offset, offset + batchSize - 1);

            if (batch != null && !batch.isEmpty()) {
                for (ZSetOperations.TypedTuple<String> tuple : batch) {
                    sum += tuple.getScore();
                }
            }
            offset += batchSize;
        }

        double avg = sum / count;
        return BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);
    }

    @Override
    public Integer getResourceDownLoadCount(Long resourceId) {
        String key = StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, resourceId);
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return -1;
        }



        return Integer.parseInt(value);
    }

    @Override
    @Transactional
    public void checkFavoriteTimes(String key, Integer batchSize) {
        // 批量更新游戏收藏数量
        Long size = redisTemplate.opsForSet().size(key);
        if (size == null || size == 0) {
            log.debug("没有要同步的收藏数");
            return;
        }

        int totalProcessed = 0;
        while (true) {
            List<String> pop = redisTemplate.opsForSet().pop(key, batchSize);
            if (CollUtils.isEmpty(pop)) {
                break;
            }

            Set<Long> gameIds = pop.stream().map(Long::valueOf).collect(Collectors.toSet());
            List<Game> list = new ArrayList<>();

            for (Long gameId : gameIds) {
                String countStr = redisTemplate.opsForValue().get(StringUtils.format(FAVORITE_COUNT_KEY, gameId));
                long count = countStr == null ? 0L : Long.parseLong(countStr);

                Game game = new Game();
                game.setId(gameId);
                game.setFavoriteCount((int) count);  // count 不会超过 int 范围
                list.add(game);
            }

            if (!list.isEmpty()) {
                gameMapper.batchUpdateFavoriteCount(list);
                totalProcessed += list.size();
            }
        }

        log.info("收藏数同步完成, 共处理 {} 个游戏", totalProcessed);
    }
}