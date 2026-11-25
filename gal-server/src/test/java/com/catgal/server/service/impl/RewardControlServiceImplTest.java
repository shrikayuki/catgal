package com.catgal.server.service.impl;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.RewardRuntime;
import com.catgal.server.mapper.*;
import com.catgal.server.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RewardControlServiceImplTest {
    private final RewardRuntimeMapper runtime = mock(RewardRuntimeMapper.class);
    private final IConfirmedRewardPublisher publisher = mock(IConfirmedRewardPublisher.class);
    private RewardControlServiceImpl service;
    private RewardRuntime state;
    @BeforeEach void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RewardControlServiceImpl(runtime, mock(UserMapper.class), mock(IUserAccountStatusService.class),
                publisher, mock(AmqpAdmin.class), mock(RabbitListenerEndpointRegistry.class),
                mock(JdbcTemplate.class), new TransactionTemplate(manager), mock(StringRedisTemplate.class), mock(Environment.class),
                new ObjectMapper(), Clock.systemUTC());
        state = new RewardRuntime(); state.setMode("LEGACY"); state.setBaselineCursor(0L);
        when(runtime.current()).thenReturn(state); when(runtime.lockShared()).thenReturn(state);
        ReflectionTestUtils.setField(service, "ready", true);
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }
    @Test void legacySourceRunsOnlyWhileSharedGateIsLegacy() {
        assertEquals("done", service.legacySource(() -> "done"));
        verify(runtime).lockShared(); state.setMode("DRAINING");
        assertEquals(50321, assertThrows(BusinessException.class, () -> service.legacySource(() -> fail("must not write"))).getCode());
    }
    @Test void drainingStillAllowsOldConsumerButActiveRejectsIt() {
        state.setMode("DRAINING"); assertEquals("done", service.legacyDrain(() -> "done"));
        state.setMode("ACTIVE");
        assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,
                () -> service.legacyDrain(() -> fail("old message must be isolated")));
    }
    @Test void newWriteRequiresActualTransactionAndActiveSharedGate() {
        state.setMode("ACTIVE"); assertThrows(IllegalStateException.class, service::requireActiveTransaction);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertDoesNotThrow(service::requireActiveTransaction);
        state.setMode("DRAINING"); assertThrows(BusinessException.class, service::requireActiveTransaction);
    }
    @Test void missingRuntimeRowCannotSilentlyEnableOldPath() {
        when(runtime.current()).thenReturn(null); assertThrows(BusinessException.class, service::mode);
    }
    @Test void activeOutboxPublishIsNotReportedAsALegacyCutoverBlocker() {
        state.setMode("ACTIVE");
        when(publisher.inFlight()).thenReturn(1);
        var blockers = (java.util.List<?>) service.status().get("blockers");
        assertFalse(blockers.contains("旧积分消息仍在等待发送确认"));
    }
}
