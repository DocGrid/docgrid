package com.opensource.docgrid.domain.document.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 마지막 정상 고아 Object Snapshot과 실패 Counter의 보존 계약을 검증한다. */
@DisplayName("StorageOrphanMetrics 테스트")
class StorageOrphanMetricsTest {

    @Test
    @DisplayName("실패가 발생해도 마지막 정상 Snapshot Gauge를 유지한다")
    void recordFailure_preservesLastSuccessfulSnapshot() {
        Instant now = Instant.parse("2026-09-19T00:00:00Z");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StorageOrphanMetrics metrics = new StorageOrphanMetrics(
            registry,
            Clock.fixed(now, ZoneOffset.UTC)
        );
        StorageOrphanInspectionResult result = new StorageOrphanInspectionResult(
            9L, 4L, 2L, 1_024L, 2L, 1L
        );

        metrics.recordSuccess(result, now.minusSeconds(10));
        metrics.recordFailure();

        assertThat(gauge(registry, "docgrid.storage.orphan.candidates")).isEqualTo(2.0);
        assertThat(gauge(registry, "docgrid.storage.orphan.bytes")).isEqualTo(1_024.0);
        assertThat(gauge(registry, "docgrid.storage.orphan.snapshot.age.seconds")).isEqualTo(10.0);
        assertThat(registry.get("docgrid.storage.orphan.inspections")
            .tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("docgrid.storage.orphan.inspections")
            .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
