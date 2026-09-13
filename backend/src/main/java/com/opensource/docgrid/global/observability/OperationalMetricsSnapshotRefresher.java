package com.opensource.docgrid.global.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 전용 단일 Thread에서 운영 DB Snapshot을 주기적으로 갱신한다.
 *
 * <p>기존 Worker·RAG·Outbox Scheduler와 실행 자원을 공유하지 않으므로 긴 Queue 작업이 관측 상태 갱신을
 * 지연시키지 않는다. 각 실행은 실패를 격리해 다음 주기에도 계속 시도한다.
 */
@Component
@Slf4j
public class OperationalMetricsSnapshotRefresher {

    private final OperationalMetricsSnapshotQueryService queryService;
    private final OperationalMetrics operationalMetrics;
    private final Clock clock;
    private final long refreshIntervalMillis;
    private final AtomicBoolean started = new AtomicBoolean();

    private ScheduledExecutorService executor;

    /** 양수인 갱신 주기를 밀리초 단위로 고정해 Scheduler의 busy loop를 막는다. */
    public OperationalMetricsSnapshotRefresher(
        OperationalMetricsSnapshotQueryService queryService,
        OperationalMetrics operationalMetrics,
        Clock clock,
        @Value("${management.metrics.docgrid.snapshot-interval:15s}") Duration refreshInterval
    ) {
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("운영 Metrics Snapshot 갱신 주기는 0보다 커야 합니다.");
        }
        this.queryService = queryService;
        this.operationalMetrics = operationalMetrics;
        this.clock = clock;
        this.refreshIntervalMillis = Math.max(1L, refreshInterval.toMillis());
    }

    /** 애플리케이션 준비 후 즉시 첫 Snapshot을 읽고 고정 지연 방식으로 다음 갱신을 예약한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "operational-metrics-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshSafely, 0L, refreshIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** 테스트와 Scheduler가 같은 실패 격리 경로를 사용하도록 한 번의 갱신을 명시적으로 실행한다. */
    void refreshSafely() {
        try {
            // 1. 모든 집계가 같은 now를 사용하도록 관측 시각을 한 번만 만든다.
            LocalDateTime observedAt = LocalDateTime.now(clock);
            OperationalMetricsSnapshot snapshot = queryService.load(observedAt);

            // 2. Query가 모두 성공한 경우에만 마지막 정상 Snapshot과 성공 Counter를 갱신한다.
            operationalMetrics.recordRefreshSuccess(snapshot, clock.instant());
        } catch (RuntimeException exception) {
            // 3. 실패는 이전 Snapshot을 보존한 채 Counter와 로그에만 남겨 다음 주기에서 재시도한다.
            operationalMetrics.recordRefreshFailure();
            log.warn("[OBSERVABILITY] 운영 Metrics Snapshot 갱신 실패", exception);
        }
    }

    /** 애플리케이션 종료 시 전용 Thread가 남지 않도록 새 실행을 중단한다. */
    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
