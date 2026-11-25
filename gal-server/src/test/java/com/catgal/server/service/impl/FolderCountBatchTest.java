package com.catgal.server.service.impl;

import com.catgal.common.utils.StringUtils;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteItemService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderCountBatchTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final FavoriteFolderServiceImpl service = new FavoriteFolderServiceImpl(
            mock(FavoriteFolderMapper.class), mock(FavoriteItemMapper.class), mock(GameMapper.class),
            mock(IFavoriteItemService.class), redis);

    @Test
    void readsDistinctFolderCountsWithOnePipeline() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands scripts = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripts);
        when(redis.getStringSerializer()).thenReturn(RedisSerializer.string());
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            assertNull(callback.doInRedis(connection));
            return List.of(4L, 0L);
        });
        assertEquals(Map.of(1L, 4, 2L, 0), service.getGameCountsFromFolders(Arrays.asList(1L, 1L, null, 2L)));
        verify(redis, times(1)).executePipelined(any(RedisCallback.class));
        verify(scripts, times(2)).eval(any(byte[].class), eq(ReturnType.INTEGER), eq(2), any(byte[].class), any(byte[].class));
    }

    @Test
    void nullScalarFolderKeepsTheOriginalUnknownCountResult() {
        assertEquals(-1, service.getGameCountFromfolder(null));
        verifyNoInteractions(redis);
    }

    @Test
    void emptyInputAvoidsRedis() {
        assertTrue(service.getGameCountsFromFolders(null).isEmpty());
        assertTrue(service.getGameCountsFromFolders(List.of()).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureAndIncompleteResultsUseDatabaseFallback() {
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(List.of(2L))
                .thenThrow(new RedisConnectionFailureException("test outage"));
        assertTrue(service.getGameCountsFromFolders(List.of(1L, 2L)).isEmpty());
        assertTrue(service.getGameCountsFromFolders(List.of(1L, 2L)).isEmpty());
    }

    @Test
    void confirmedZeroIsReturnedWhileMissingAndOverflowCountsUseDatabaseFallback() {
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenReturn(Arrays.asList(0L, null, Long.MAX_VALUE, 2L));
        assertEquals(Map.of(1L, 0, 4L, 2), service.getGameCountsFromFolders(List.of(1L, 2L, 3L, 4L)));
    }
}
