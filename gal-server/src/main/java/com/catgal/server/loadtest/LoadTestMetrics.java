/*
 * 阅读说明
 * 并发测试的线程安全指标汇总，记录成功率、吞吐和延迟分布。
 */
package com.catgal.server.loadtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, bounded latency samples. All updates and snapshots use the same monitor. */
public final class LoadTestMetrics {
    public record Summary(int started, int completed, int succeeded, int failed, int cancelled, int inFlight,
                   double successRate, double rps, double averageMs, double p50Ms, double p95Ms,
                   double p99Ms, double maxMs) { }
    public record Point(int second, int completed, int failed, double rps, double p95Ms) { }

    private final long[] latencies;
    private final List<List<Long>> seconds = new ArrayList<>();
    private final Map<String, Integer> errors = new LinkedHashMap<>();
    private final int[] failedPerSecond = new int[70];
    private int started;
    private int succeeded;
    private int failed;
    private int cancelled;
    private int samples;
    private long sum;

    LoadTestMetrics(int capacity) {
        latencies = new long[capacity];
        for (int i = 0; i < failedPerSecond.length; i++) seconds.add(new ArrayList<>());
    }

    synchronized boolean begin(int maximum) {
        if (started >= maximum) return false;
        started++;
        return true;
    }

    synchronized void record(long nanos, int second, LoadTestHttpClient.Outcome outcome) {
        if ("CANCELLED".equals(outcome.error())) {
            cancelled++;
            return;
        }
        int bucket = Math.max(0, Math.min(second, failedPerSecond.length - 1));
        latencies[samples++] = nanos;
        sum += nanos;
        seconds.get(bucket).add(nanos);
        if (outcome.success()) succeeded++;
        else {
            failed++;
            failedPerSecond[bucket]++;
            errors.merge(outcome.error(), 1, Integer::sum);
        }
    }

    synchronized Summary summary(double elapsed) {
        long[] values = Arrays.copyOf(latencies, samples);
        Arrays.sort(values);
        int finished = succeeded + failed;
        return new Summary(started, finished + cancelled, succeeded, failed, cancelled,
                started - finished - cancelled, finished == 0 ? 0 : 100.0 * succeeded / finished,
                elapsed <= 0 ? 0 : finished / elapsed, samples == 0 ? 0 : sum / (double) samples / 1_000_000,
                percentile(values, .50), percentile(values, .95), percentile(values, .99), percentile(values, 1));
    }

    synchronized List<Point> timeline(double elapsed) {
        List<Point> points = new ArrayList<>();
        int last = Math.min((int) elapsed, seconds.size() - 1);
        for (int i = 0; i <= last; i++) {
            long[] values = seconds.get(i).stream().mapToLong(Long::longValue).sorted().toArray();
            double width = Math.min(1, Math.max(.001, elapsed - i));
            points.add(new Point(i, values.length, failedPerSecond[i], values.length / width, percentile(values, .95)));
        }
        return points;
    }

    synchronized Map<String, Integer> errors() { return Map.copyOf(errors); }

    synchronized String safetyReason() {
        int finished = succeeded + failed;
        if (finished < 20 || finished % 20 != 0) return null;
        if (failed * 5 >= finished) return "ERROR_RATE_LIMIT";
        long[] values = Arrays.copyOf(latencies, samples);
        Arrays.sort(values);
        return percentile(values, .95) > 3000 ? "LATENCY_LIMIT" : null;
    }

    private static double percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) return 0;
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)] / 1_000_000.0;
    }
}
