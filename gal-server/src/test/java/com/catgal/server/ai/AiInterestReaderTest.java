package com.catgal.server.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.server.ai.tools.AiInterestReader;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameReviewMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiInterestReaderTest {
    private final FavoriteFolderMapper folders = mock(FavoriteFolderMapper.class);
    private final FavoriteItemMapper items = mock(FavoriteItemMapper.class);
    private final GameReviewMapper reviews = mock(GameReviewMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AiInterestReader reader = new AiInterestReader(folders, items, reviews, redis);

    @BeforeAll static void tables() {
        for (Class<?> type : List.of(FavoriteFolder.class, FavoriteItem.class, GameReview.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @BeforeEach void setup() {
        FavoriteFolder folder = new FavoriteFolder().setId(1L).setUserId(7L).setIsPublic(0);
        when(folders.selectList(any())).thenReturn(List.of(folder));
        when(reviews.selectList(any())).thenReturn(List.of());
    }

    @Test void pendingOrProcessingEmptyHashMustNotResurrectPersistedFavorites() {
        when(items.selectList(any())).thenReturn(List.of(
                new FavoriteItem().setFolderId(1L).setUserId(7L).setGameId(11L),
                new FavoriteItem().setFolderId(1L).setUserId(7L).setGameId(12L)));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(List.of(1L, 0L));
        var result = reader.read(7, 5);
        assertTrue(result.favoriteIds().isEmpty());
        assertTrue(result.sampled());
        assertEquals("current", result.sourceStatus());
        verifyNoInteractions(items);
    }

    @Test void currentCacheIncludesUnflushedAdditionsAndRejectsOtherOwners() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(
                List.of(1L, 0L, "12", "7", "99", "8", "bad", "7"));
        assertEquals(List.of(12L), reader.read(7, 5).favoriteIds());
        verifyNoInteractions(items);
    }

    @Test void coldHashWithOnlyNewFavoriteIsASampleAndDoesNotMergePossiblyRemovedDatabaseRows() {
        when(items.selectList(any())).thenReturn(List.of(
                new FavoriteItem().setFolderId(1L).setUserId(7L).setGameId(11L),
                new FavoriteItem().setFolderId(1L).setUserId(7L).setGameId(12L)));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(
                List.of(1L, 0L, "13", "7"));
        var result = reader.read(7, 5);
        assertEquals(List.of(13L), result.favoriteIds());
        assertTrue(result.sampled());
        assertEquals("current", result.sourceStatus());
        verifyNoInteractions(items);
    }

    @Test void absentCurrentCacheReadsOnlyOwnedFolderRowsAndMarksFallback() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(List.of(0L, 0L));
        when(items.selectList(any())).thenReturn(List.of(
                new FavoriteItem().setFolderId(1L).setUserId(7L).setGameId(11L),
                new FavoriteItem().setFolderId(1L).setUserId(8L).setGameId(99L)));
        var result = reader.read(7, 5);
        assertEquals(List.of(11L), result.favoriteIds());
        assertEquals("database_fallback", result.sourceStatus());
        ArgumentCaptor<LambdaQueryWrapper<FavoriteItem>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(items).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
    }

    @Test void redisFailureHasExplicitFreshnessStatus() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("private connection info"));
        when(items.selectList(any())).thenReturn(List.of());
        assertEquals("database_fallback", reader.read(7, 5).sourceStatus());
    }

    @Test void boundedSnapshotSetsSamplingAndOnlySelectsStructuredReviewFields() {
        List<Object> values = new ArrayList<>(List.of(1L, 1L));
        for (int i = 1; i <= 201; i++) { values.add(String.valueOf(i)); values.add("7"); }
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(values);
        var result = reader.read(7, 5);
        assertEquals(200, result.favoriteIds().size());
        assertTrue(result.sampled());
        ArgumentCaptor<LambdaQueryWrapper<GameReview>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviews).selectList(query.capture());
        assertFalse(query.getValue().getSqlSelect().contains("brief_review"));
        assertTrue(query.getValue().getSqlSelect().contains("play_status"));
    }

    @Test void foldersBelongingToAnotherUserAreNeverRead() {
        when(folders.selectList(any())).thenReturn(List.of(new FavoriteFolder().setId(99L).setUserId(8L)));
        assertTrue(reader.read(7, 5).favoriteIds().isEmpty());
        verifyNoInteractions(redis, items);
    }
}
