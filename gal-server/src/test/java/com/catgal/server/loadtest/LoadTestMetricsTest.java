package com.catgal.server.loadtest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadTestMetricsTest {
    @Test void percentilesFailuresAndExactCounters() {
        LoadTestMetrics metrics = new LoadTestMetrics(4);
        for (int i = 1; i <= 4; i++) {
            assertTrue(metrics.begin(4));
            metrics.record(i * 25_000_000L, i % 2, new LoadTestHttpClient.Outcome(i != 4, i == 4 ? "HTTP_500" : null));
        }
        assertFalse(metrics.begin(4));
        LoadTestMetrics.Summary value = metrics.summary(2);
        assertEquals(62.5, value.averageMs());
        assertEquals(50, value.p50Ms());
        assertEquals(100, value.p95Ms());
        assertEquals(100, value.p99Ms());
        assertEquals(75, value.successRate());
        assertEquals(2, value.rps());
        assertEquals(1, metrics.errors().get("HTTP_500"));
        assertEquals(4, metrics.timeline(2).stream().mapToInt(LoadTestMetrics.Point::completed).sum());
    }

    @Test void emptyAndCancelledSamplesRemainFiniteAndLatencyGuardStopsSlowRuns() {
        LoadTestMetrics empty = new LoadTestMetrics(1);
        assertEquals(0, empty.summary(0).p95Ms());
        empty.begin(1);
        empty.record(1, 0, new LoadTestHttpClient.Outcome(false, "CANCELLED"));
        assertEquals(1, empty.summary(1).cancelled());
        assertEquals(0, empty.summary(1).inFlight());
        LoadTestMetrics slow = new LoadTestMetrics(20);
        for (int i = 0; i < 20; i++) { slow.begin(20); slow.record(3_100_000_000L, 3, new LoadTestHttpClient.Outcome(true, null)); }
        assertEquals("LATENCY_LIMIT", slow.safetyReason());
    }
}
