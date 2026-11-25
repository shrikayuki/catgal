/*
 * 阅读说明
 * 数据库提交后更新已发布游戏 ID 缓存，附带版本以处理提交回调乱序。
 * 启动对账和分批修正用于恢复派生的随机游戏候选集合。
 */
package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.catgal.common.constants.RedisConstant.GAME_IDS_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;

@Component
@RequiredArgsConstructor
@Slf4j
public class GamePublicationCache {
    private static final DefaultRedisScript<Long> APPLY = load("lua/game-catalog/published-cache.lua", Long.class);
    private static final String REVISIONS_KEY = "gal:game:published:revisions";
    private final StringRedisTemplate redis;
    private final GameMapper games;
    private long reconcileAfterId;

    // 数据库事务成功后才应用公开 ID 缓存，版本标记防止迟到回调覆盖新状态。
    public void afterCommit(Game game) {
        Long id = game.getId();
        Long revision = game.getRevision();
        Integer status = game.getStatus();
        Runnable update = () -> {
            try {
                apply(id, revision, status);
            } catch (RuntimeException exception) {
                log.warn("Game publication cache deferred to reconciliation, gameId={}, failureType={}", id, exception.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                // 数据库事务成功后才应用公开 ID 缓存，版本标记防止迟到回调覆盖新状态。
                public void afterCommit() { update.run(); }
            });
        } else update.run();
    }

    public void initialize() {
        long afterId = 0;
        List<Game> batch;
        while (!(batch = batch(afterId)).isEmpty()) {
            for (Game game : batch) apply(game.getId(), game.getRevision(), game.getStatus());
            afterId = batch.get(batch.size() - 1).getId();
        }
    }

    @Scheduled(fixedDelayString = "${catgal.catalog.publication-cache-reconcile-delay-ms:30000}")
    public synchronized void reconcile() {
        try {
            List<Game> batch = batch(reconcileAfterId);
            if (batch.isEmpty()) {
                reconcileAfterId = 0;
                return;
            }
            for (Game game : batch) apply(game.getId(), game.getRevision(), game.getStatus());
            reconcileAfterId = batch.get(batch.size() - 1).getId();
        } catch (RuntimeException exception) {
            log.warn("Game publication cache reconciliation will retry, failureType={}", exception.getClass().getSimpleName());
        }
    }

    private List<Game> batch(long afterId) {
        return games.selectList(new LambdaQueryWrapper<Game>().select(Game::getId, Game::getRevision, Game::getStatus)
                .gt(Game::getId, afterId).orderByAsc(Game::getId).last("LIMIT 200"));
    }

    private void apply(Long id, Long revision, Integer status) {
        redis.execute(APPLY, List.of(GAME_IDS_KEY, REVISIONS_KEY), id.toString(), revision.toString(), String.valueOf(status));
    }
}
