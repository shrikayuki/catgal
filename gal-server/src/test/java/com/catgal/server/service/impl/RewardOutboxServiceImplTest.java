package com.catgal.server.service.impl;

import com.catgal.server.domain.po.*;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RewardOutboxServiceImplTest {
    private final RewardOutboxMapper events = mock(RewardOutboxMapper.class);
    private final RewardRequestMapper requests = mock(RewardRequestMapper.class);
    private final UserCutePointsMapper balances = mock(UserCutePointsMapper.class);
    private final IConfirmedRewardPublisher publisher = mock(IConfirmedRewardPublisher.class);
    private final IRewardControlService control = mock(IRewardControlService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private RewardOutboxServiceImpl service;
    private RewardOutbox event;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RewardOutboxServiceImpl(events, requests, balances, publisher, control, redis, jdbc, new TransactionTemplate(manager));
        when(control.isActive()).thenReturn(true);
        event = new RewardOutbox(); event.setId(7L); event.setRequestId(9L); event.setAttempts(2);
        when(events.claimOne()).thenReturn(event); when(events.selectById(7L)).thenReturn(event);
    }
    @Test void legacyModeCannotDispatchNewEvents() {
        when(control.isActive()).thenReturn(false); service.dispatchOne(); verifyNoInteractions(events, publisher, jdbc);
    }
    @Test void publisherConfirmationMovesOnlyOwnedUncompletedLeaseToSent() {
        service.dispatchOne();
        verify(publisher).send(eq(RewardOutboxServiceImpl.EXCHANGE), eq(RewardOutboxServiceImpl.ROUTING_KEY), any());
        verify(jdbc).update(contains("state='SENT'"), eq(7L), eq(event.getLeaseToken()));
        assertNotNull(event.getLeaseToken());
    }
    @Test void failedSendKeepsDurableRetryAndUsesExceptionTypeOnly() {
        doThrow(new IllegalStateException("private URL and secret fixture")).when(publisher).send(anyString(), anyString(), any());
        service.dispatchOne();
        verify(jdbc).update(contains("state='FAILED'"), eq("IllegalStateException"), eq(15), eq(7L), eq(event.getLeaseToken()));
    }
    @Test void alreadyProjectedEventIsAcknowledgedWithoutReadingOrChangingBalance() {
        event.setProjectedAt(LocalDateTime.now()); service.project(7L);
        verifyNoInteractions(requests, balances, redis, jdbc);
    }
    @Test void missingOrInvalidSourceCannotProduceCacheWrites() {
        assertThrows(RuntimeException.class, () -> service.project(7L));
        verifyNoInteractions(balances, redis);
        verify(jdbc).update(contains("state='FAILED'"), eq("BusinessException"), eq(7L));
    }
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void redisFailureLeavesEventUncompletedForRetry() {
        RewardRequest request = new RewardRequest(); request.setUserId(101L); request.setStatus("COMPLETED");
        when(requests.selectById(9L)).thenReturn(request);
        when(balances.selectById(101L)).thenReturn(new UserCutePoints().setUserId(101L).setCutePoints(10).setTotalEarned(10).setTotalSpent(0).setRevision(4L));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("isolated Redis failure"));
        assertThrows(IllegalStateException.class, () -> service.project(7L));
        verify(jdbc).update(contains("state='FAILED'"), eq("IllegalStateException"), eq(7L));
    }
}
