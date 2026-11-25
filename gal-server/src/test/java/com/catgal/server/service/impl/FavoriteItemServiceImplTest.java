package com.catgal.server.service.impl;

import Message.FavoriteSyncMessage;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.RedisDirtySetProcessor;
import java.util.*;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.PlatformTransactionManager;

import static com.catgal.common.constants.RedisConstant.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class FavoriteItemServiceImplTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final FavoriteFolderMapper folders = mock(FavoriteFolderMapper.class);
    private final FavoriteItemMapper items = mock(FavoriteItemMapper.class);
    private final GameMapper games = mock(GameMapper.class);
    private final RabbitMqHelper mq = mock(RabbitMqHelper.class);
    private final RedisDirtySetProcessor processor = mock(RedisDirtySetProcessor.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private final FavoriteItemServiceImpl service = spy(new FavoriteItemServiceImpl(
            redis, folders, mq, items, games, new CurrentUserProvider(), processor, mock(PlatformTransactionManager.class)));
    private final Map<String, List<?>> snapshots = new HashMap<>();
    private Long mutation = 5L;
    private Long hydrate = 0L;

    @BeforeEach void setup() {
        for (Class<?> type : List.of(FavoriteItem.class, FavoriteFolder.class, Game.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(folders.selectForUpdate(10L)).thenReturn(new FavoriteFolder().setId(10L).setUserId(6L));
        when(folders.update(any(), any())).thenReturn(1);
        when(games.selectForUpdate(20L)).thenReturn(new Game().setId(20L).setStatus(1).setFavoriteCount(4));
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(values.get(anyString())).thenReturn("4");
        when(items.selectList(any())).thenReturn(List.of());
        snapshots.put("user:favorite:10", List.of(1L, "0"));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            String script = ((RedisScript<?>) call.getArgument(0)).getScriptAsString();
            List<String> keys = call.getArgument(1);
            if (script.contains("原子读取完整状态")) return snapshots.getOrDefault(keys.get(0), List.of(0L));
            if (script.contains("仅无 pending/processing")) return hydrate;
            if (script.contains("Invalid favorite operation")) return mutation;
            return 1L;
        });
        doReturn(true).when(service).saveBatch(anyCollection());
    }

    @AfterEach void clear() { UserContext.clear(); }

    private FavoriteDTO request() {
        FavoriteDTO dto = new FavoriteDTO(); dto.setFolderId(10L); dto.setGameId(20L); return dto;
    }

    private void claimedFolders(String... ids) {
        doAnswer(call -> {
            ((Consumer<List<String>>) call.getArgument(3)).accept(List.of(ids));
            return ids.length;
        }).when(processor).processBatch(eq(FAVORITE_CHANGE_SET_KEY), eq(100), eq(true), any());
    }

    @Test void mutationLocksFolderBeforeGameAndPublishesTheCompatiblePayload() {
        assertTrue(service.favorite(request()));
        var order = inOrder(folders, games, redis);
        order.verify(folders).selectForUpdate(10L);
        order.verify(games).selectForUpdate(20L);
        order.verify(redis).execute(any(RedisScript.class), eq(List.of("user:favorite:10", "user:favorite:expected:10")));
        var captured = org.mockito.ArgumentCaptor.forClass(FavoriteSyncMessage.class);
        verify(mq).send(anyString(), anyString(), captured.capture());
        assertEquals(1, captured.getValue().getOperation());
        assertEquals(6L, captured.getValue().getUserId());
    }

    @Test void duplicateAddAndRemoveDoNotPublishAnotherEvent() {
        mutation = -1L;
        assertFalse(service.favorite(request())); assertFalse(service.unfavorite(request()));
        verifyNoInteractions(mq);
    }

    @Test void lostPendingCacheReturns503WithoutDeletingDatabaseRelations() {
        snapshots.clear();
        assertEquals(503, assertThrows(BusinessException.class, () -> service.favorite(request())).getHttpStatus());
        assertEquals(503, assertThrows(BusinessException.class, () -> service.unfavorite(request())).getHttpStatus());
        verify(items, never()).delete(any()); verify(items, never()).deleteByFolderIds(any()); verifyNoInteractions(mq);
    }

    @Test void mutationDetectsEvictionBetweenSnapshotAndAtomicChange() {
        mutation = -2L;
        assertEquals(503, assertThrows(BusinessException.class, () -> service.favorite(request())).getHttpStatus());
        verifyNoInteractions(mq);
    }

    @Test void missingAndUnpublishedGamesAreRejectedBeforeCacheWrites() {
        when(games.selectForUpdate(20L)).thenReturn(null, new Game().setStatus(0), new Game().setStatus(2));
        for (int index = 0; index < 3; index++) {
            assertEquals(404, assertThrows(BusinessException.class, () -> service.favorite(request())).getHttpStatus());
        }
        verifyNoInteractions(redis, mq);
    }

    @Test void hiddenOrRemovedGamesCanStillBeUnfavoritedAndZeroIsPublished() {
        mutation = 0L;
        when(games.selectForUpdate(20L)).thenReturn(new Game().setId(20L).setStatus(2), null);
        assertTrue(service.unfavorite(request())); assertTrue(service.unfavorite(request()));
        verify(mq, times(2)).send(anyString(), anyString(), any(FavoriteSyncMessage.class));
    }

    @Test void anonymousAndOtherOwnersCannotMutateClearOrDelete() {
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, () -> service.favorite(request())).getHttpStatus());
        assertEquals(401, assertThrows(BusinessException.class, () -> service.unfavoriteBatch(10L)).getHttpStatus());
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.unfavorite(request())).getHttpStatus());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.unfavoriteBatch(10L)).getHttpStatus());
        assertEquals(403, assertThrows(BusinessException.class, () -> service.deleteFolder(10L)).getHttpStatus());
        verifyNoInteractions(redis, mq, games);
    }

    @Test void invalidServiceRequestsAreRejected() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.favorite(null)).getHttpStatus());
        FavoriteDTO dto = request(); dto.setGameId(0L);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.unfavorite(dto)).getHttpStatus());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.unfavoriteBatch(-1L)).getHttpStatus());
        verifyNoInteractions(redis, folders, games, mq);
    }

    @Test void wholeBatchSnapshotsAreCheckedBeforeAnyDatabaseDeletion() {
        when(folders.selectForUpdate(11L)).thenReturn(new FavoriteFolder().setId(11L).setUserId(6L));
        claimedFolders("10", "11");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.fullSyncFavoriteItemsToDBTask(100)).getHttpStatus());
        verify(items, never()).deleteByFolderIds(any()); verify(folders, never()).update(any(), any());
        verify(service, never()).saveBatch(anyCollection());
    }

    @Test void confirmedEmptySnapshotDeletesRelationshipsAndUpdatesFolderCount() {
        claimedFolders("10"); service.fullSyncFavoriteItemsToDBTask(100);
        verify(items).deleteByFolderIds(Set.of(10L));
        verify(folders).update(isNull(), argThat(query -> query.getSqlSet().contains("game_count")));
        verify(service, never()).saveBatch(anyCollection());
    }

    @Test void wrongOwnerSnapshotCannotReachDatabaseDeletion() {
        snapshots.put("user:favorite:10", List.of(1L, "1", "20", "999")); claimedFolders("10");
        assertEquals(503, assertThrows(BusinessException.class, () -> service.fullSyncFavoriteItemsToDBTask(100)).getHttpStatus());
        verify(items, never()).deleteByFolderIds(any());
    }

    @Test void verifiedSnapshotKeepsOwnerAndGameIds() {
        snapshots.put("user:favorite:10", List.of(1L, "1", "20", "6")); claimedFolders("10");
        service.fullSyncFavoriteItemsToDBTask(100);
        var captured = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(service).saveBatch(captured.capture());
        FavoriteItem item = (FavoriteItem) captured.getValue().iterator().next();
        assertEquals(10L, item.getFolderId()); assertEquals(20L, item.getGameId()); assertEquals(6L, item.getUserId());
    }

    @Test void batchClearUsesOneAtomicScriptWithoutIntermediateMqMutations() {
        snapshots.put("user:favorite:10", List.of(1L, "1", "20", "6"));
        assertTrue(service.unfavoriteBatch(10L));
        verify(redis).execute(argThat((RedisScript<Long> script) -> script.getScriptAsString().contains("完整预检后一次性")),
                anyList(), eq("1"), eq("10"), eq("clear"), eq("20"), eq("1"));
        verifyNoInteractions(mq);
    }

    @Test void batchClearAbortsWhenHistoricalCacheIsMissing() {
        snapshots.clear();
        assertEquals(503, assertThrows(BusinessException.class, () -> service.unfavoriteBatch(10L)).getHttpStatus());
        verifyNoInteractions(mq);
    }

    @Test void messageOwnerMismatchIsPoisonBeforeReadingRedis() {
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> service.syncFavorite(10L, 7L, 20L));
        verifyNoInteractions(redis, games);
    }

    @Test void staleAddNotificationDeletesWhenCurrentSnapshotIsEmpty() {
        service.syncFavorite(10L, 6L, 20L);
        verify(items).delete(any()); verify(items, never()).insert(any(FavoriteItem.class));
    }

    @Test void staleRemoveNotificationInsertsWhenCurrentSnapshotContainsTheGame() {
        snapshots.put("user:favorite:10", List.of(1L, "1", "20", "6"));
        service.syncFavorite(10L, 6L, 20L);
        verify(items).insert(argThat((FavoriteItem item) -> item.getFolderId() == 10L && item.getUserId() == 6L && item.getGameId() == 20L));
        verify(items, never()).delete(any());
    }

    @Test void missingGameOnlyCleansScopedHistoricalRows() {
        when(games.selectForUpdate(20L)).thenReturn(null);
        snapshots.put("user:favorite:10", List.of(1L, "1", "20", "6"));
        service.syncFavorite(10L, 6L, 20L);
        verify(items).delete(argThat(query -> query.getCustomSqlSegment().contains("user_id")
                && query.getCustomSqlSegment().contains("folder_id") && query.getCustomSqlSegment().contains("game_id")));
        verify(items, never()).insert(any(FavoriteItem.class));
    }

    @Test void mqOutageLeavesTheAtomicDirtyTaskAsRecoveryPath() {
        doThrow(new IllegalStateException("isolated broker failure")).when(mq).send(anyString(), anyString(), any());
        assertTrue(service.favorite(request()));
        verify(redis, never()).delete(anyString());
    }

    @Test void allFolderLocksPrecedeSortedGameLocksInFullSync() {
        when(folders.selectForUpdate(11L)).thenReturn(new FavoriteFolder().setId(11L).setUserId(6L));
        when(games.selectForUpdate(21L)).thenReturn(new Game().setId(21L));
        snapshots.put("user:favorite:10", List.of(1L, "1", "21", "6"));
        snapshots.put("user:favorite:11", List.of(1L, "1", "20", "6"));
        claimedFolders("11", "10"); service.fullSyncFavoriteItemsToDBTask(100);
        var order = inOrder(folders, games);
        order.verify(folders).selectForUpdate(10L); order.verify(folders).selectForUpdate(11L);
        order.verify(games).selectForUpdate(20L); order.verify(games).selectForUpdate(21L);
    }

    @Test void missingCounterIncludesUnpersistedDifferenceFromBothDirtySets() {
        when(values.get(anyString())).thenReturn(null);
        when(items.selectByGameForUpdate(20L)).thenReturn(List.of(new FavoriteItem().setFolderId(10L)));
        when(sets.union(FAVORITE_CHANGE_SET_KEY, FAVORITE_CHANGE_SET_KEY + ":processing")).thenReturn(Set.of("10", "11"));
        snapshots.put("user:favorite:11", List.of(1L, "1", "20", "7"));
        // SQL 中 10 有收藏，当前 10 已取消、11 新增尚未落库，重算仍为 1。
        assertTrue(service.favorite(request()));
        verify(values).set("favorite:count:20", "1");
    }

    @Test void malformedCounterFormatsAreNormalizedBeforeTheAtomicMutation() {
        when(items.selectByGameForUpdate(20L)).thenReturn(List.of());
        when(sets.union(FAVORITE_CHANGE_SET_KEY, FAVORITE_CHANGE_SET_KEY + ":processing")).thenReturn(Set.of());
        for (String malformed : List.of("+1", "-0", "broken", "2147483648")) {
            when(values.get(anyString())).thenReturn(malformed);
            assertTrue(service.favorite(request()));
        }
        verify(values, times(4)).set("favorite:count:20", "0");
    }

    @Test void completedTasksDisappearingAfterTheInitialUnionDoNotBlockCounterRecovery() {
        when(values.get(anyString())).thenReturn(null);
        when(items.selectByGameForUpdate(20L)).thenReturn(List.of());
        when(sets.union(FAVORITE_CHANGE_SET_KEY, FAVORITE_CHANGE_SET_KEY + ":processing"))
                .thenReturn(Set.of("11"), Set.of());
        assertTrue(service.favorite(request()));
        verify(values).set("favorite:count:20", "0");
    }

    @Test void missingCounterCannotGuessAnUnverifiablePendingFolder() {
        when(values.get(anyString())).thenReturn(null);
        when(items.selectByGameForUpdate(20L)).thenReturn(List.of());
        when(sets.union(FAVORITE_CHANGE_SET_KEY, FAVORITE_CHANGE_SET_KEY + ":processing")).thenReturn(Set.of("11"));
        assertEquals(503, assertThrows(BusinessException.class, () -> service.favorite(request())).getHttpStatus());
        verify(values, never()).set(anyString(), anyString()); verifyNoInteractions(mq);
    }
}
