package com.catgal.server.catalog;

import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GamePublicationCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final GameMapper mapper = mock(GameMapper.class);
    private final GamePublicationCache cache = new GamePublicationCache(redis, mapper);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void waitsForTransactionCommitBeforeUpdatingRedis() {
        TransactionSynchronizationManager.initSynchronization();
        cache.afterCommit(new Game().setId(1L).setRevision(3L).setStatus(1));
        verifyNoInteractions(redis);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(redis).execute(any(RedisScript.class), anyList(), eq("1"), eq("3"), eq("1"));
    }

    @Test
    void transactionRollbackDoesNotChangePublishedIds() {
        TransactionSynchronizationManager.initSynchronization();
        cache.afterCommit(new Game().setId(1L).setRevision(3L).setStatus(1));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureAfterCommitDoesNotReportDatabaseWriteAsFailed() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("offline"));
        assertDoesNotThrow(() -> cache.afterCommit(new Game().setId(1L).setRevision(1L).setStatus(2)));
    }
}
