package com.catgal.server.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.GAME_IDS_KEY;

@Component
@Slf4j
@RequiredArgsConstructor
public class GameIdsCacheTask {

    private final GameMapper gameMapper;
    private final StringRedisTemplate redisTemplate;


    @PostConstruct
    public void initGameIds() {
        // 异步加载，不阻塞启动
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 查询所有游戏ID
                List<Long> ids = gameMapper.selectList(
                        new LambdaQueryWrapper<Game>()
                                .eq(Game::getStatus, 1)
                                .select(Game::getId)
                ).stream().map(Game::getId).collect(Collectors.toList());

                if (ids.isEmpty()) {
                    log.warn("没有已发布的游戏");
                    return;
                }

                // 2. 批量添加到 Redis
                String[] idArray = ids.stream()
                        .map(String::valueOf)
                        .toArray(String[]::new);
                redisTemplate.opsForSet().add(GAME_IDS_KEY, idArray);

                log.info("游戏ID缓存加载完成，共 {} 条", ids.size());
            } catch (Exception e) {
                log.error("游戏ID缓存加载失败", e);
            }
        });
    }

    /**
     * 随机获取一个游戏ID
     */
    public Long getRandomGameId() {
        String randomId = redisTemplate.opsForSet().randomMember(GAME_IDS_KEY);
        return Long.parseLong(randomId);
    }
}