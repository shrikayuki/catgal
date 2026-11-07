package com.catgal.server.service.listener;

import Message.UserCutePointsMessage;
import com.catgal.common.enums.PointsType;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.service.IUserCutePointsService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CutePointsListenerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final IUserCutePointsService pointsService = mock(IUserCutePointsService.class);
    private final CutePointsListener listener = new CutePointsListener(redisTemplate, pointsService);

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void duplicateMessageIsAcknowledgedWithoutApplyingPointsAgain() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-2L);
        UserCutePointsMessage message = UserCutePointsMessage.builder()
                .bizId("sign:1:2026-11-07")
                .userId(1L)
                .pointsType(PointsType.SIGN_IN)
                .cutePointsChange(1)
                .build();

        assertDoesNotThrow(() -> listener.addSignInCache(message));
        verify(pointsService).getUserCutePointsCache(1L);
    }

    @Test
    void rejectsMessageWithoutBusinessIdBeforeTouchingRedis() {
        UserCutePointsMessage message = UserCutePointsMessage.builder()
                .userId(1L)
                .pointsType(PointsType.SIGN_IN)
                .cutePointsChange(1)
                .build();

        assertThrows(BusinessException.class, () -> listener.addSignInCache(message));
        verify(pointsService, never()).getUserCutePointsCache(1L);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void rejectsPointsTypeWhoseDirectionDoesNotMatchDelta() {
        UserCutePointsMessage message = UserCutePointsMessage.builder()
                .bizId("bad-direction")
                .userId(1L)
                .pointsType(PointsType.LOTTERY_COST)
                .cutePointsChange(1)
                .build();

        assertThrows(BusinessException.class, () -> listener.addSignInCache(message));
    }
}
