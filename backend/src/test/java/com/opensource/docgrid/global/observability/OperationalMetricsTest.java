package com.opensource.docgrid.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 운영 Snapshot이 DB 접근 없이 Gauge로 변환되고 실패 때 마지막 정상 값을 보존하는지 검증한다.
 */
@DisplayName("운영 상태 Gauge 테스트")
class OperationalMetricsTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZONE_ID);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OperationalMetrics metrics = new OperationalMetrics(meterRegistry, clock);

    @Test
    @DisplayName("마지막 정상 Snapshot의 Queue 수와 현재 기준 나이를 Gauge로 제공한다")
    void exposesLastSuccessfulSnapshotAsGauges() {
        LocalDateTime now = LocalDateTime.now(clock);
        metrics.recordRefreshSuccess(new OperationalMetricsSnapshot(
            3,
            2,
            now.minusSeconds(75),
            1,
            4,
            now.minusSeconds(120),
            5,
            1,
            now.minusSeconds(30)
        ), NOW.minusSeconds(10));

        assertThat(gauge("docgrid.embedding.claimable.jobs")).isEqualTo(3.0);
        assertThat(gauge("docgrid.embedding.processing.jobs")).isEqualTo(2.0);
        assertThat(gauge("docgrid.embedding.oldest.claimable.age.seconds")).isEqualTo(75.0);
        assertThat(gauge("docgrid.embedding.active.workers")).isEqualTo(1.0);
        assertThat(gauge("docgrid.rag.processing.jobs")).isEqualTo(4.0);
        assertThat(gauge("docgrid.rag.oldest.processing.age.seconds")).isEqualTo(120.0);
        assertThat(gauge("docgrid.sync.outbox.claimable.events")).isEqualTo(5.0);
        assertThat(gauge("docgrid.sync.outbox.processing.events")).isEqualTo(1.0);
        assertThat(gauge("docgrid.sync.outbox.oldest.claimable.age.seconds")).isEqualTo(30.0);
        assertThat(gauge("docgrid.operational.snapshot.age.seconds")).isEqualTo(10.0);
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("갱신 실패는 마지막 정상 Gauge를 0으로 덮어쓰지 않는다")
    void retainsLastGoodSnapshotAfterRefreshFailure() {
        LocalDateTime now = LocalDateTime.now(clock);
        metrics.recordRefreshSuccess(new OperationalMetricsSnapshot(
            2, 0, now.minusSeconds(15), 1, 0, null, 0, 0, null
        ), NOW);

        metrics.recordRefreshFailure();

        assertThat(gauge("docgrid.embedding.claimable.jobs")).isEqualTo(2.0);
        assertThat(gauge("docgrid.embedding.oldest.claimable.age.seconds")).isEqualTo(15.0);
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("첫 정상 갱신 전에는 Snapshot 나이를 무한대로 노출한다")
    void exposesInfiniteAgeBeforeFirstSuccessfulRefresh() {
        assertThat(gauge("docgrid.operational.snapshot.age.seconds")).isPositive().isInfinite();
        assertThat(gauge("docgrid.embedding.claimable.jobs")).isZero();
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double counter(String outcome) {
        return meterRegistry.get("docgrid.operational.snapshot.refresh")
            .tag("outcome", outcome)
            .counter()
            .count();
    }
}
