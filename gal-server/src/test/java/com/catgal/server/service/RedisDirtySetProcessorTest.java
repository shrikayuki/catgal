package com.catgal.server.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static com.catgal.server.redis.RedisScriptLoader.load;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class RedisDirtySetProcessorTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final RedisDirtySetProcessor processor = new RedisDirtySetProcessor(redis, transactions);
    private final List<String> calls = new ArrayList<>();
    private RuntimeException requeueFailure;
    private RuntimeException releaseFailure;
    private RuntimeException ackFailure;
    private Long ackResult = 1L;
    private List<String> claimed = List.of("1");
    private String token;

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("lock:dirty-set:pending")).thenAnswer(invocation -> token);
        when(values.setIfAbsent(eq("lock:dirty-set:pending"), anyString(), eq(Duration.ofMinutes(5))))
                .thenAnswer(invocation -> {
                    token = invocation.getArgument(1);
                    return true;
                });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            Object[] arguments = (Object[]) invocation.getRawArguments()[2];
            assertEquals(token, arguments[0]);
            if (script.getSha1().equals(load("lua/dirty-set/claim.lua", List.class).getSha1())) {
                calls.add("claim");
                assertEquals("10", arguments[1]);
                return claimed;
            }
            if (script.getSha1().equals(load("lua/dirty-set/requeue-all.lua", Long.class).getSha1())) {
                calls.add("recover");
                return 0L;
            }
            if (script.getSha1().equals(load("lua/dirty-set/ack.lua", Long.class).getSha1())) {
                calls.add("ack");
                assertEquals("1", arguments[1]);
                if (ackFailure != null) throw ackFailure;
                return ackResult;
            }
            if (script.getSha1().equals(load("lua/dirty-set/requeue.lua", Long.class).getSha1())) {
                calls.add("requeue");
                assertEquals("1", arguments[1]);
                if (requeueFailure != null) throw requeueFailure;
                return 1L;
            }
            calls.add("release");
            if (releaseFailure != null) throw releaseFailure;
            return 1L;
        });
    }

    @Test
    void rejectsInvalidInputBeforeAcquiringALock() {
        assertThrows(IllegalArgumentException.class, () -> processor.processBatch(null, 10, false, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.processBatch(" ", 10, false, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.processBatch("pending", 0, false, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.processBatch("pending", -1, false, ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> processor.processBatch("pending", 10, false, null));
        verifyNoInteractions(redis, transactions);
    }

    @Test
    void busyLockDoesNotTouchAnotherWorkersProcessing() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertEquals(0, processor.processBatch("pending", 10, false, ignored -> fail("Lock is busy")));
        assertTrue(calls.isEmpty());
    }

    @Test
    void usesTheSameOwnerTokenThroughRecoveryClaimAndAcknowledgement() {
        assertEquals(1, processor.processBatch("pending", 10, true,
                members -> assertEquals(List.of("1"), members)));
        assertEquals(List.of("recover", "claim", "ack", "release"), calls);
        verify(transactions).executeWithoutResult(any());
        verify(redis, never()).opsForSet();
    }

    @Test
    void keepsOriginalFailureWhenRequeueAndUnlockAlsoFail() {
        RuntimeException original = new IllegalStateException("original database failure");
        requeueFailure = new IllegalStateException("redis requeue failed");
        releaseFailure = new IllegalStateException("redis unlock failed");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> processor.processBatch("pending", 10, false, ignored -> { throw original; }));
        assertSame(original, thrown);
        assertArrayEquals(new Throwable[]{requeueFailure, releaseFailure}, thrown.getSuppressed());
        assertEquals(List.of("recover", "claim", "requeue", "release"), calls);
    }

    @Test
    void acknowledgementFailureAttemptsRecoveryAndRemainsThePrimaryError() {
        ackFailure = new IllegalStateException("ack unavailable");
        assertSame(ackFailure, assertThrows(RuntimeException.class,
                () -> processor.processBatch("pending", 10, false, ignored -> {})));
        assertEquals(List.of("recover", "claim", "ack", "requeue", "release"), calls);
    }

    @Test
    void ownershipLossCannotBeReportedAsASuccessfulBatch() {
        ackResult = -1L;
        assertThrows(IllegalStateException.class, () -> processor.processBatch("pending", 10, false, ignored -> {}));
        assertEquals(List.of("recover", "claim", "ack", "requeue", "release"), calls);
    }

    @Test
    void emptyBatchReleasesLockWithoutAcknowledgement() {
        claimed = List.of();
        assertEquals(0, processor.processBatch("pending", 10, false, ignored -> fail("Empty batch")));
        assertEquals(List.of("recover", "claim", "release"), calls);
    }

    @Test
    void unlockFailureAfterSuccessfulAckIsStillObservable() {
        releaseFailure = new IllegalStateException("unlock unavailable");
        assertSame(releaseFailure, assertThrows(RuntimeException.class,
                () -> processor.processBatch("pending", 10, false, ignored -> {})));
        assertEquals(List.of("recover", "claim", "ack", "release"), calls);
    }

    @Test
    void transactionCallbackDetectsALostOwnerBeforeReturningForCommit() {
        assertThrows(IllegalStateException.class, () -> processor.processBatch("pending", 10, true,
                ignored -> when(values.get("lock:dirty-set:pending")).thenReturn("successor")));
        assertEquals(List.of("recover", "claim", "requeue", "release"), calls);
    }
}
