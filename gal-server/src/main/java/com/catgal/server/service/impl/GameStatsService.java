package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class GameStatsService implements IGameStatsService{

    private final StringRedisTemplate redisTemplate;
    private final GameMapper gameMapper;
    private final ResourceMapper resourceMapper;

    @Override
    public Integer getGameLookCount(Long id) {
        String key = StringUtils.format(LOOK_COUNT_KEY, id);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            // 缓存未命中，从数据库查
            Game game = gameMapper.selectById(id);
            if (game == null) {
                return 0;
            }
            // 回写缓存
            redisTemplate.opsForValue().set(key, String.valueOf(game.getViewCount()), 1, TimeUnit.HOURS);
            return game.getViewCount();
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameFavoriteCount(Long gameId) {
        String key = StringUtils.format(FAVORITE_COUNT_KEY, gameId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            // 缓存未命中，从数据库查
            Game game = gameMapper.selectById(gameId);
            if (game == null) {
                return 0;
            }
            // 回写缓存
            redisTemplate.opsForValue().set(key, String.valueOf(game.getFavoriteCount()), 1, TimeUnit.HOURS);
            return game.getFavoriteCount();
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getGameResourceCount(Long gameId) {
        String key = StringUtils.format(RESOURCE_COUNT_KEY, gameId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            // 缓存未命中，从数据库统计资源数量
            Integer count = resourceMapper.selectCount(
                    new LambdaQueryWrapper<Resource>()
                            .eq(Resource::getGameId, gameId)
                            .eq(Resource::getStatus, 1)
            ).intValue();
            // 回写缓存
            redisTemplate.opsForValue().set(key, String.valueOf(count), 1, TimeUnit.HOURS);
            return count;
        }
        return Integer.parseInt(value);
    }

    @Override
    public Integer getResourceDownLoadCount(Long resourceId) {
        String key = StringUtils.format(RESOURCE_DOWNLOAD_COUNT_KEY, resourceId);
        String value = redisTemplate.opsForValue().get(key);

        if (value != null) {
            return Integer.parseInt(value);
        }

        // 缓存未命中，查数据库
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            return 0;
        }

        Integer downloadCount = resource.getDownloadCount();

        // 回写缓存（5分钟过期）
        redisTemplate.opsForValue().set(key, String.valueOf(downloadCount), 5, TimeUnit.MINUTES);

        return downloadCount;
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

    @Override
    public Integer getGameDownLoadCount(Long id) {
        return 0;
    }

    @Override
    public Integer getGameCommentCount(Long id) {
        return 0;
    }

    @Override
    public BigDecimal getRating(Long id) {
        return null;
    }
}