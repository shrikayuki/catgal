package com.catgal.server.loadtest;

import com.catgal.common.exception.BusinessException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoadTestServiceTest {
    private final LoadTestProperties properties = new LoadTestProperties();
    private final LoadTestHttpClient http = mock(LoadTestHttpClient.class);
    private final LoadTestService service = new LoadTestService(properties, http);
    private static final LoadTestHttpClient.Outcome OK = new LoadTestHttpClient.Outcome(true, null);

    @AfterEach void close() { service.close(); }

    private LoadTestService.Start input(String scenario, int concurrency, int requests) {
        return new LoadTestService.Start(UUID.randomUUID().toString(), scenario, concurrency, 2, requests, null, null);
    }

    private LoadTestService.View finished(String id) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < limit) {
            LoadTestService.View view = service.get(id);
            if (view.finishedAt() != null) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Task did not finish");
    }

    @Test void disabledFeatureNeverCallsTarget() {
        assertFalse(service.catalog().enabled());
        assertEquals(503, assertThrows(BusinessException.class, () -> service.start(input("games", 1, 1), 6L)).getHttpStatus());
        verifyNoInteractions(http);
    }

    @Test void exactRequestCapAndBusinessMetricsAndIdempotency() throws Exception {
        properties.setEnabled(true);
        when(http.request(anyString(), anyBoolean())).thenReturn(OK);
        LoadTestService.Start request = input("games", 7, 100);
        LoadTestService.View first = service.start(request, 6L);
        LoadTestService.View done = finished(first.id());
        assertEquals("COMPLETED", done.state());
        assertEquals("REQUEST_LIMIT", done.stopReason());
        assertEquals(100, done.metrics().started());
        assertEquals(100, done.metrics().succeeded());
        assertEquals(0, done.metrics().inFlight());
        assertEquals(100, done.metrics().successRate());
        assertEquals(100, done.timeline().stream().mapToInt(LoadTestMetrics.Point::completed).sum());
        assertEquals(first.id(), service.start(request, 6L).id());
        verify(http, times(101)).request("http://127.0.0.1:8080/game/page?pageNo=1&pageSize=12", false);
        assertThrows(BusinessException.class, () -> service.start(request, 7L));
    }

    @Test void cooperativeStopKeepsMutexUntilInflightRequestsFinish() throws Exception {
        properties.setEnabled(true);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(http.request(anyString(), anyBoolean())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
            }
            return OK;
        });
        try {
            String id = service.start(input("games", 2, 1000), 6L).id();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("STOPPING", service.cancel(id).state());
            assertThrows(BusinessException.class, () -> service.start(input("games", 1, 1), 6L));
            release.countDown();
            LoadTestService.View done = finished(id);
            assertEquals("STOPPED", done.state());
            assertEquals("USER_STOPPED", done.stopReason());
            assertEquals(2, done.metrics().completed());
            assertEquals(0, done.metrics().inFlight());
        } finally { release.countDown(); }
    }

    @Test void failedPreflightAndErrorRateStopHaveDistinctStates() throws Exception {
        properties.setEnabled(true);
        when(http.request(anyString(), anyBoolean())).thenReturn(new LoadTestHttpClient.Outcome(false, "HTTP_503"));
        LoadTestService.View preflight = finished(service.start(input("games", 1, 50), 6L).id());
        assertEquals("FAILED", preflight.state());
        assertEquals(0, preflight.metrics().started());
        assertEquals("HTTP_503", preflight.stopReason());
        when(http.request(anyString(), anyBoolean())).thenReturn(OK, new LoadTestHttpClient.Outcome(false, "HTTP_500"));
        LoadTestService.View guarded = finished(service.start(input("games", 3, 5000), 6L).id());
        assertEquals("STOPPED", guarded.state());
        assertEquals("ERROR_RATE_LIMIT", guarded.stopReason());
        assertTrue(guarded.metrics().failed() >= 20 && guarded.metrics().failed() < 5000);
    }

    @Test void validatesScenarioTargetAndNeverAcceptsUnboundedScale() {
        properties.setEnabled(true);
        assertThrows(BusinessException.class, () -> service.start(input("http://external.invalid", 1, 1), 6L));
        assertThrows(BusinessException.class, () -> service.start(input("comments", 1, 1), 6L));
        assertThrows(BusinessException.class, () -> service.start(input("search", 1, 1), 6L));
        assertThrows(BusinessException.class, () -> service.start(input("games", 51, 1), 6L));
        properties.setTargetPrefix("//external.invalid");
        assertThrows(BusinessException.class, service::catalog);
        verifyNoInteractions(http);
    }

    @Test void historyRetentionIsBoundedAndShutdownRejectsNewRuns() throws Exception {
        properties.setEnabled(true);
        when(http.request(anyString(), anyBoolean())).thenReturn(OK);
        String first = null;
        String latest = null;
        for (int i = 0; i < 21; i++) {
            String id = service.start(input("games", 1, 1), 6L).id();
            latest = id;
            if (first == null) first = id;
            finished(id);
        }
        assertEquals(20, service.list().size());
        assertEquals(latest, service.list().get(0).id());
        String evicted = first;
        assertThrows(BusinessException.class, () -> service.get(evicted));
        service.close();
        assertThrows(BusinessException.class, () -> service.start(input("games", 1, 1), 6L));
    }

    @Test void unexpectedWorkerFailureDrainsOtherWorkersBeforeFinishing() throws Exception {
        properties.setEnabled(true);
        when(http.request(anyString(), anyBoolean())).thenReturn(OK).thenThrow(new IllegalStateException("private-response"));
        LoadTestService.View done = finished(service.start(input("games", 2, 100), 6L).id());
        assertEquals("FAILED", done.state());
        assertEquals("RUNNER_UNAVAILABLE", done.stopReason());
        assertFalse(done.toString().contains("private-response"));
        assertEquals(0, done.metrics().inFlight());
    }

    @Test void deadlineStopsNewRequestsAndEncodesOnlyScenarioParameters() throws Exception {
        properties.setEnabled(true);
        properties.setTargetPort(8088);
        properties.setTargetPrefix("/api");
        when(http.request(anyString(), anyBoolean())).thenAnswer(invocation -> { Thread.sleep(40); return OK; });
        LoadTestService.Start request = new LoadTestService.Start(UUID.randomUUID().toString(), "search", 1, 1, 5000, null, " 测试 ");
        LoadTestService.View done = finished(service.start(request, 6L).id());
        assertEquals("DURATION_LIMIT", done.stopReason());
        assertTrue(done.metrics().completed() > 0 && done.metrics().completed() < 5000);
        assertTrue(done.elapsedSeconds() >= 1 && done.elapsedSeconds() < 2);
        verify(http, atLeast(2)).request("http://127.0.0.1:8088/api/game/page?pageNo=1&pageSize=12&keyword=%E6%B5%8B%E8%AF%95", false);
    }

    @Test void cancellingDuringPreflightPreservesUserStopEvenIfTargetThenFails() throws Exception {
        properties.setEnabled(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(http.request(anyString(), anyBoolean())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return new LoadTestHttpClient.Outcome(false, "HTTP_503");
        });
        try {
            String id = service.start(input("games", 1, 100), 6L).id();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            service.cancel(id);
            release.countDown();
            LoadTestService.View done = finished(id);
            assertEquals("STOPPED", done.state());
            assertEquals("USER_STOPPED", done.stopReason());
            assertEquals(0, done.metrics().started());
        } finally { release.countDown(); }
    }
}
