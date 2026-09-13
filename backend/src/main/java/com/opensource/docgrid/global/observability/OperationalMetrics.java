package com.opensource.docgrid.global.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 마지막 정상 DB Snapshot을 메모리에 보관하고 이를 Micrometer Gauge로 노출한다.
 *
 * <p>Gauge callback은 AtomicReference와 Clock만 읽는다. DB 갱신 실패 시 이전 값을 유지하고 Snapshot
 * 나이와 갱신 실패 Counter를 올려, 0으로 덮인 값이 정상 상태로 오인되는 것을 막는다.
 */
@Component
public class OperationalMetrics {

    private final Clock clock;
    private final AtomicReference<SnapshotState> state = new AtomicReference<>(SnapshotState.uninitialized());
    private final Counter refreshSuccess;
    private final Counter refreshFailure;

    /** 모든 Gauge와 고정 outcome Counter를 애플리케이션 시작 시 한 번 등록한다. */
    public OperationalMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;

        registerGauge(meterRegistry, "docgrid.embedding.claimable.jobs",
            "Embedding jobs that can be claimed now",
            snapshot -> snapshot.embeddingClaimableJobs());
        registerGauge(meterRegistry, "docgrid.embedding.processing.jobs",
            "Embedding jobs currently processing",
            snapshot -> snapshot.embeddingProcessingJobs());
        registerGauge(meterRegistry, "docgrid.embedding.oldest.claimable.age.seconds",
            "Age in seconds of the oldest claimable embedding job",
            snapshot -> ageSeconds(snapshot.embeddingOldestClaimableAt()));
        registerGauge(meterRegistry, "docgrid.embedding.active.workers",
            "Embedding workers with a live heartbeat",
            snapshot -> snapshot.embeddingActiveWorkers());
        registerGauge(meterRegistry, "docgrid.rag.processing.jobs",
            "RAG responses currently processing",
            snapshot -> snapshot.ragProcessingJobs());
        registerGauge(meterRegistry, "docgrid.rag.oldest.processing.age.seconds",
            "Age in seconds of the oldest processing RAG response",
            snapshot -> ageSeconds(snapshot.ragOldestProcessingAt()));
        registerGauge(meterRegistry, "docgrid.sync.outbox.claimable.events",
            "Sync Outbox events that can be claimed now",
            snapshot -> snapshot.syncClaimableEvents());
        registerGauge(meterRegistry, "docgrid.sync.outbox.processing.events",
            "Sync Outbox events currently processing",
            snapshot -> snapshot.syncProcessingEvents());
        registerGauge(meterRegistry, "docgrid.sync.outbox.oldest.claimable.age.seconds",
            "Age in seconds of the oldest claimable Sync Outbox event",
            snapshot -> ageSeconds(snapshot.syncOldestClaimableAt()));

        Gauge.builder("docgrid.operational.snapshot.age.seconds", state, this::snapshotAgeSeconds)
            .description("Seconds since the last successful operational snapshot refresh")
            .register(meterRegistry);

        refreshSuccess = Counter.builder("docgrid.operational.snapshot.refresh")
            .description("Operational snapshot refresh results")
            .tag("outcome", "success")
            .register(meterRegistry);
        refreshFailure = Counter.builder("docgrid.operational.snapshot.refresh")
            .description("Operational snapshot refresh results")
            .tag("outcome", "failed")
            .register(meterRegistry);
    }

    /** 세 Queue Query가 모두 성공했을 때만 기존 Snapshot을 한 번에 교체한다. */
    public void recordRefreshSuccess(OperationalMetricsSnapshot snapshot, Instant refreshedAt) {
        state.set(new SnapshotState(snapshot, refreshedAt));
        refreshSuccess.increment();
    }

    /** 실패 횟수만 기록하고 마지막 정상 Snapshot은 그대로 보존한다. */
    public void recordRefreshFailure() {
        refreshFailure.increment();
    }

    private void registerGauge(
        MeterRegistry meterRegistry,
        String name,
        String description,
        SnapshotValue value
    ) {
        Gauge.builder(name, state, reference -> {
            OperationalMetricsSnapshot snapshot = reference.get().snapshot();
            return snapshot == null ? 0.0 : value.read(snapshot);
        }).description(description).register(meterRegistry);
    }

    private double ageSeconds(LocalDateTime oldestAt) {
        if (oldestAt == null) {
            return 0.0;
        }
        Duration age = Duration.between(oldestAt, LocalDateTime.now(clock));
        return Math.max(0.0, age.toMillis() / 1_000.0);
    }

    private double snapshotAgeSeconds(AtomicReference<SnapshotState> reference) {
        Instant refreshedAt = reference.get().refreshedAt();
        if (refreshedAt == null) {
            return Double.POSITIVE_INFINITY;
        }
        Duration age = Duration.between(refreshedAt, clock.instant());
        return Math.max(0.0, age.toMillis() / 1_000.0);
    }

    /** 등록된 각 Gauge가 Snapshot에서 하나의 숫자를 읽는 내부 함수 경계다. */
    @FunctionalInterface
    private interface SnapshotValue {
        double read(OperationalMetricsSnapshot snapshot);
    }

    /** 마지막 정상 Snapshot과 그 갱신 시각을 하나의 AtomicReference 값으로 묶는다. */
    private record SnapshotState(OperationalMetricsSnapshot snapshot, Instant refreshedAt) {

        private static SnapshotState uninitialized() {
            return new SnapshotState(null, null);
        }
    }
}
