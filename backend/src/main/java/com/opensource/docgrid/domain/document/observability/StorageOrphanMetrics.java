package com.opensource.docgrid.domain.document.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 마지막으로 완료된 고아 Object 탐지 Snapshot과 성공·실패 횟수를 Micrometer로 노출한다.
 * 실패한 부분 스캔은 이전 정상 Snapshot을 덮지 않아 불완전한 수치를 정상 결과로 보이지 않게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "storage.orphan-gc", name = "enabled", havingValue = "true")
public class StorageOrphanMetrics {

    private final Clock clock;
    private final AtomicReference<SnapshotState> state = new AtomicReference<>(SnapshotState.uninitialized());
    private final Counter inspectionSuccess;
    private final Counter inspectionFailure;

    /** 저빈도 Snapshot Gauge와 고정 outcome Counter를 한 번 등록한다. */
    public StorageOrphanMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.clock = clock;

        registerGauge(meterRegistry, "docgrid.storage.orphan.scanned.objects",
            "Storage objects examined by the last completed orphan inspection",
            result -> result.scannedObjects());
        registerGauge(meterRegistry, "docgrid.storage.orphan.referenced.objects",
            "Storage objects referenced by FileObject rows in the last completed inspection",
            result -> result.referencedObjects());
        registerGauge(meterRegistry, "docgrid.storage.orphan.candidates",
            "Old unreferenced storage objects found by the last completed inspection",
            result -> result.orphanCandidates());
        registerGauge(meterRegistry, "docgrid.storage.orphan.bytes",
            "Bytes occupied by orphan candidates in the last completed inspection",
            result -> result.orphanCandidateBytes());
        registerGauge(meterRegistry, "docgrid.storage.orphan.recent.objects",
            "Objects excluded by the grace period in the last completed inspection",
            result -> result.recentObjects());
        registerGauge(meterRegistry, "docgrid.storage.orphan.invalid.objects",
            "Objects excluded by location, key, timestamp, or size validation",
            result -> result.invalidObjects());
        Gauge.builder("docgrid.storage.orphan.snapshot.age.seconds", state, this::snapshotAgeSeconds)
            .description("Seconds since the last completed storage orphan inspection")
            .register(meterRegistry);

        inspectionSuccess = Counter.builder("docgrid.storage.orphan.inspections")
            .description("Storage orphan inspection results")
            .tag("outcome", "success")
            .register(meterRegistry);
        inspectionFailure = Counter.builder("docgrid.storage.orphan.inspections")
            .description("Storage orphan inspection results")
            .tag("outcome", "failed")
            .register(meterRegistry);
    }

    /** 전체 저장소 Stream과 모든 DB 비교가 끝난 결과만 마지막 정상 Snapshot으로 교체한다. */
    public void recordSuccess(StorageOrphanInspectionResult result, Instant inspectedAt) {
        state.set(new SnapshotState(result, inspectedAt));
        inspectionSuccess.increment();
    }

    /** 실패 Counter만 증가시키고 마지막 정상 Snapshot은 유지한다. */
    public void recordFailure() {
        inspectionFailure.increment();
    }

    private void registerGauge(
        MeterRegistry meterRegistry,
        String name,
        String description,
        SnapshotValue value
    ) {
        Gauge.builder(name, state, reference -> {
            StorageOrphanInspectionResult result = reference.get().result();
            return result == null ? 0.0 : value.read(result);
        }).description(description).register(meterRegistry);
    }

    private double snapshotAgeSeconds(AtomicReference<SnapshotState> reference) {
        Instant inspectedAt = reference.get().inspectedAt();
        if (inspectedAt == null) {
            return Double.POSITIVE_INFINITY;
        }
        Duration age = Duration.between(inspectedAt, clock.instant());
        return Math.max(0.0, age.toMillis() / 1_000.0);
    }

    /** 각 Gauge가 탐지 Snapshot에서 하나의 값만 읽도록 제한하는 내부 함수 경계다. */
    @FunctionalInterface
    private interface SnapshotValue {
        double read(StorageOrphanInspectionResult result);
    }

    /** 마지막 정상 탐지 결과와 완료 시각을 하나의 원자 값으로 보관한다. */
    private record SnapshotState(StorageOrphanInspectionResult result, Instant inspectedAt) {

        private static SnapshotState uninitialized() {
            return new SnapshotState(null, null);
        }
    }
}
