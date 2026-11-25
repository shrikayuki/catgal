/*
 * 阅读说明
 * 异步创建和管理本进程并发测试，限制任务互斥、时长、并发和请求总量。
 * 停止时先停止新请求，在途请求结束后释放任务名额，结果保留在有界历史中。
 */
package com.catgal.server.loadtest;

import com.catgal.common.exception.BusinessException;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
public class LoadTestService {
    public record Start(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientRequestId,
            @NotBlank String scenarioId,
            @NotNull @Min(1) @Max(50) Integer concurrency,
            @NotNull @Min(1) @Max(60) Integer durationSeconds,
            @NotNull @Min(1) @Max(5000) Integer maxRequests,
            @Positive @JsonSerialize(using = ToStringSerializer.class) Long gameId,
            @Size(max = 100) String keyword) { }
    public record Scenario(String id, String label, String path, boolean requiresGame, boolean requiresKeyword) { }
    public record Catalog(boolean enabled, String target, int maxConcurrency, int maxDurationSeconds,
                          int maxRequests, int retainedRuns, String activeJobId, List<Scenario> scenarios) { }
    public record View(String id, Start config, Long actorId, String state, String stopReason,
                       String createdAt, String startedAt, String finishedAt, double elapsedSeconds,
                       String target, LoadTestMetrics.Summary metrics, List<LoadTestMetrics.Point> timeline,
                       Map<String, Integer> errors) { }

    private final LoadTestProperties properties;
    private final LoadTestHttpClient http;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "load-test-job"));
    private final ExecutorService workers = Executors.newFixedThreadPool(50, runnable -> daemon(runnable, "load-test-request"));
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private String activeId;
    private boolean closed;

    public LoadTestService(LoadTestProperties properties, LoadTestHttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public synchronized Catalog catalog() {
        List<Scenario> scenarios = java.util.Arrays.stream(LoadTestScenario.values()).map(value ->
                new Scenario(value.id, value.label, value.path, value.requiresGame, value.requiresKeyword)).toList();
        return new Catalog(properties.isEnabled(), target(), 50, 60, 5000, 20, activeId, scenarios);
    }

    public synchronized List<View> list() {
        List<View> recent = new ArrayList<>(jobs.values().stream().map(Job::view).toList());
        Collections.reverse(recent);
        return List.copyOf(recent);
    }

    public synchronized View get(String id) { return find(id).view(); }

    // 先校验固定场景和预算，再占用本进程任务槽位并异步执行压测。
    public synchronized View start(Start input, Long actorId) {
        if (!properties.isEnabled() || closed) throw new BusinessException(503, 503, "并发测试尚未启用或服务正在停止");
        if (actorId == null) throw BusinessException.unauthorized("管理员上下文不存在");
        LoadTestScenario scenario = LoadTestScenario.find(input.scenarioId());
        String keyword = input.keyword() == null ? null : input.keyword().trim();
        if (scenario.requiresGame != (input.gameId() != null)
                || scenario.requiresKeyword != (keyword != null && !keyword.isBlank())) {
            throw BusinessException.badRequest("请按场景填写游戏 ID 或检索词");
        }
        if (input.concurrency() < 1 || input.concurrency() > 50 || input.durationSeconds() < 1
                || input.durationSeconds() > 60 || input.maxRequests() < 1 || input.maxRequests() > 5000) {
            throw BusinessException.badRequest("测试规模超出允许范围");
        }
        Start config = new Start(input.clientRequestId(), scenario.id, input.concurrency(),
                input.durationSeconds(), input.maxRequests(), input.gameId(), scenario.requiresKeyword ? keyword : null);
        for (Job previous : jobs.values()) {
            if (previous.config.clientRequestId().equals(config.clientRequestId())) {
                if (!previous.config.equals(config) || !previous.actorId.equals(actorId)) {
                    throw BusinessException.conflict("请求标识已使用，请重新读取任务");
                }
                return previous.view();
            }
        }
        if (activeId != null) throw BusinessException.conflict("当前进程已有测试，请等待结束或停止当前任务");
        if (jobs.size() >= 20) jobs.remove(jobs.keySet().iterator().next());
        String base = target();
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(base + scenario.path)
                .queryParam("pageNo", 1).queryParam("pageSize", 12);
        if (scenario.requiresGame) uri.queryParam("gameId", input.gameId());
        if (scenario.requiresKeyword) uri.queryParam("keyword", keyword);
        Job job = new Job(config, actorId, uri.build().encode().toUriString(), base);
        jobs.put(job.id, job);
        activeId = job.id;
        coordinator.submit(() -> execute(job, scenario));
        log.info("Load test started id={} actor={} scenario={} concurrency={}", job.id, actorId, scenario.id, config.concurrency());
        return job.view();
    }

    // 发出停止信号，已在途请求完成收尾后才释放任务互斥与最终指标。
    public synchronized View cancel(String id) {
        Job job = find(id);
        job.requestStop("USER_STOPPED");
        return job.view();
    }

    private Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) throw BusinessException.notFound("任务记录不存在或服务已重启，请刷新历史");
        return job;
    }

    private String target() {
        String prefix = properties.getTargetPrefix();
        if (properties.getTargetPort() < 1 || properties.getTargetPort() > 65535
                || !("".equals(prefix) || "/api".equals(prefix))) {
            throw new BusinessException(503, 503, "并发测试本地目标配置无效");
        }
        return "http://127.0.0.1:" + properties.getTargetPort() + prefix;
    }

    private void execute(Job job, LoadTestScenario scenario) {
        try {
            if (job.stopping.get()) return;
            LoadTestHttpClient.Outcome preflight = http.request(job.uri, scenario.requiresGame);
            if (job.stopping.get()) return;
            if (!preflight.success()) {
                job.failPreflight(preflight.error());
                return;
            }
            if (!job.beginRun()) return;
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < job.config.concurrency(); i++) {
                running.add(workers.submit(() -> runWorker(job, scenario)));
            }
            for (Future<?> future : running) {
                try { future.get(); }
                catch (ExecutionException exception) { job.failPreflight("RUNNER_UNAVAILABLE"); }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.requestStop("SERVICE_STOPPED");
        } catch (Exception exception) {
            job.failPreflight("RUNNER_UNAVAILABLE");
            log.warn("Load test runner unavailable id={} type={}", job.id, exception.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                job.finish();
                if (job.id.equals(activeId)) activeId = null;
            }
        }
    }

    private void runWorker(Job job, LoadTestScenario scenario) {
        long deadline = job.startedNanos + TimeUnit.SECONDS.toNanos(job.config.durationSeconds());
        while (!job.stopping.get() && System.nanoTime() < deadline && job.metrics.begin(job.config.maxRequests())) {
            long started = System.nanoTime();
            LoadTestHttpClient.Outcome result;
            try {
                result = http.request(job.uri, scenario.requiresGame);
                if (result == null) throw new IllegalStateException("Missing load-test outcome");
            } catch (RuntimeException exception) {
                job.failPreflight("RUNNER_UNAVAILABLE");
                result = new LoadTestHttpClient.Outcome(false, "RUNNER_UNAVAILABLE");
            }
            long finished = System.nanoTime();
            job.metrics.record(finished - started, (int) TimeUnit.NANOSECONDS.toSeconds(finished - job.startedNanos), result);
            String limit = job.metrics.safetyReason();
            if (limit != null) job.requestStop(limit);
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        jobs.values().forEach(job -> job.requestStop("SERVICE_STOPPED"));
        workers.shutdownNow();
        coordinator.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Start config;
        final Long actorId;
        final String uri;
        final String target;
        final String createdAt = Instant.now().toString();
        final LoadTestMetrics metrics;
        final AtomicBoolean stopping = new AtomicBoolean();
        volatile long startedNanos;
        long finishedNanos;
        String startedAt;
        String finishedAt;
        String state = "PREPARING";
        String stopReason;

        Job(Start config, Long actorId, String uri, String target) {
            this.config = config;
            this.actorId = actorId;
            this.uri = uri;
            this.target = target;
            metrics = new LoadTestMetrics(config.maxRequests());
        }

        synchronized boolean beginRun() {
            if (stopping.get()) return false;
            state = "RUNNING";
            startedAt = Instant.now().toString();
            startedNanos = System.nanoTime();
            return true;
        }

        synchronized void requestStop(String reason) {
            if (finishedAt != null || stopping.get()) return;
            stopping.set(true);
            stopReason = reason;
            state = "STOPPING";
        }

        synchronized void failPreflight(String error) {
            stopping.set(true);
            state = "FAILED";
            stopReason = error;
        }

        synchronized void finish() {
            finishedNanos = System.nanoTime();
            finishedAt = Instant.now().toString();
            if (!"FAILED".equals(state)) {
                state = stopping.get() ? "STOPPED" : "COMPLETED";
                if (!stopping.get()) stopReason = metrics.summary(0).started() >= config.maxRequests()
                        ? "REQUEST_LIMIT" : "DURATION_LIMIT";
            }
        }

        synchronized View view() {
            double elapsed = startedNanos == 0 ? 0 :
                    ((finishedNanos == 0 ? System.nanoTime() : finishedNanos) - startedNanos) / 1_000_000_000.0;
            return new View(id, config, actorId, state, stopReason, createdAt, startedAt, finishedAt,
                    elapsed, target, metrics.summary(elapsed), metrics.timeline(elapsed), metrics.errors());
        }
    }
}
