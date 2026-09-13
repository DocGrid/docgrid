package com.opensource.docgrid.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 운영 Snapshot 갱신 성공·실패가 Metric 저장소 경계에서 격리되는지 검증한다.
 */
@DisplayName("운영 상태 Snapshot Refresher 테스트")
class OperationalMetricsSnapshotRefresherTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OperationalMetricsSnapshotQueryService queryService =
        mock(OperationalMetricsSnapshotQueryService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OperationalMetrics metrics = new OperationalMetrics(meterRegistry, CLOCK);
    private final OperationalMetricsSnapshotRefresher refresher =
        new OperationalMetricsSnapshotRefresher(queryService, metrics, CLOCK, Duration.ofSeconds(15));

    @Test
    @DisplayName("세 집계가 성공하면 같은 결과를 게시하고 scrape는 QueryService를 호출하지 않는다")
    void refreshesSnapshotAndServesScrapesFromMemory() {
        LocalDateTime observedAt = LocalDateTime.now(CLOCK);
        OperationalMetricsSnapshot snapshot = new OperationalMetricsSnapshot(
            2, 1, observedAt.minusSeconds(30), 1, 0, null, 0, 0, null
        );
        given(queryService.load(observedAt)).willReturn(snapshot);

        refresher.refreshSafely();
        reset(queryService);

        assertThat(meterRegistry.get("docgrid.embedding.claimable.jobs").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("docgrid.embedding.claimable.jobs").gauge().value()).isEqualTo(2.0);
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("집계 예외를 밖으로 전파하지 않고 실패 Counter만 증가시킨다")
    void recordsFailureAndKeepsSchedulerAlive() {
        LocalDateTime observedAt = LocalDateTime.now(CLOCK);
        given(queryService.load(observedAt)).willThrow(new IllegalStateException("database unavailable"));

        refresher.refreshSafely();

        assertThat(meterRegistry.get("docgrid.operational.snapshot.refresh")
            .tag("outcome", "failed").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("docgrid.operational.snapshot.age.seconds").gauge().value())
            .isInfinite();
    }
}
