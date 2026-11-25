package com.catgal.server.service.impl;

import com.catgal.common.constants.RedisConstant;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.UserCutePoints;
import com.catgal.server.mapper.UserCutePointsMapper;
import com.catgal.server.service.RedisDirtySetProcessor;
import com.catgal.server.service.IRewardControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCutePointsServiceImplTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisDirtySetProcessor dirtySetProcessor = mock(RedisDirtySetProcessor.class);
    private final UserCutePointsMapper mapper = mock(UserCutePointsMapper.class);
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);
    private final UserCutePointsServiceImpl service =
            new UserCutePointsServiceImpl(redisTemplate, dirtySetProcessor, mock(IRewardControlService.class));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void incompleteDirtyBalanceCannotBeHydratedFromOlderDatabaseState() {
        when(setOperations.isMember(RedisConstant.CUTE_POINTS_CHANGE_SET_KEY, "1")).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.getUserCutePointsCache(1L));
        verify(mapper, never()).selectById(1L);
        verify(hashOperations, never()).putIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cachedZeroIsAValidBalanceAndDoesNotReloadDatabase() {
        String key = StringUtils.format(RedisConstant.CUTE_POINTS_KEY, 1L);
        when(hashOperations.get(key, RedisConstant.CUTE_POINTS_HASH_KEY)).thenReturn("0");
        when(hashOperations.get(key, RedisConstant.TOTAL_EARNED_HASH_KEY)).thenReturn("0");
        when(hashOperations.get(key, RedisConstant.TOTAL_SPENT_HASH_KEY)).thenReturn("0");

        assertEquals(0, service.getUserCutePointsCache(1L));

        verify(mapper, never()).selectById(1L);
        verify(hashOperations, never()).increment(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cacheMissInitializesWithoutIncrementingStoredBalance() {
        String key = StringUtils.format(RedisConstant.CUTE_POINTS_KEY, 2L);
        UserCutePoints stored = new UserCutePoints();
        stored.setUserId(2L);
        stored.setCutePoints(7);
        stored.setTotalEarned(10);
        stored.setTotalSpent(3);
        when(mapper.selectById(2L)).thenReturn(stored);
        when(hashOperations.get(key, RedisConstant.CUTE_POINTS_HASH_KEY))
                .thenReturn(null)
                .thenReturn("7");

        assertEquals(7, service.getUserCutePointsCache(2L));

        verify(hashOperations).putIfAbsent(key, RedisConstant.CUTE_POINTS_HASH_KEY, "7");
        verify(hashOperations).putIfAbsent(key, RedisConstant.TOTAL_EARNED_HASH_KEY, "10");
        verify(hashOperations).putIfAbsent(key, RedisConstant.TOTAL_SPENT_HASH_KEY, "3");
        verify(hashOperations, never()).increment(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
