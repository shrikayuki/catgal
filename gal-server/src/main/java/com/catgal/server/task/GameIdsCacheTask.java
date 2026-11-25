/*
 * 阅读说明
 * 初始化公开游戏候选 ID 缓存并为随机游戏入口抽取候选。
 */
package com.catgal.server.task;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.catalog.GamePublicationCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static com.catgal.common.constants.RedisConstant.GAME_IDS_KEY;

@Component
@Slf4j
@RequiredArgsConstructor
public class GameIdsCacheTask {

    private final GamePublicationCache publicationCache;
    private final StringRedisTemplate redisTemplate;


    @PostConstruct
    public void initGameIds() {
        // 异步加载，不阻塞启动
        CompletableFuture.runAsync(() -> {
            try {
                // Versioned, batched initialization also removes unpublished IDs left by earlier runs.
                publicationCache.initialize();
                log.info("游戏发布状态缓存初始化完成");
            } catch (RuntimeException exception) {
                log.warn("游戏发布状态缓存初始化待后台补偿, failureType={}", exception.getClass().getSimpleName());
            }
        });
    }

    /**
     * 随机获取一个游戏ID
     */
    public Long getRandomGameId() {
        String randomId = redisTemplate.opsForSet().randomMember(GAME_IDS_KEY);
        if (randomId == null) throw BusinessException.notFound("暂无已发布游戏");
        return Long.parseLong(randomId);
    }
}
