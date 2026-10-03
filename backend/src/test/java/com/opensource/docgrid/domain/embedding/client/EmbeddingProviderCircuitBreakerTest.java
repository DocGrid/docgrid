package com.opensource.docgrid.domain.embedding.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitBreaker.CallPermission;
import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Embedding Provider Circuit의 연속 실패, Open fast-fail과 단일 Half-open Probe 상태 전이·결과
 * Metric을 검증한다.
 *
 * <p>실제 HTTP 호출과 Worker Job 재예약은 제외하고 동시 Permission 발급과 Clock 기반 복구 경계만
 * 확인한다.
 */
@DisplayName("EmbeddingProviderCircuitBreaker 테스트")
class EmbeddingProviderCircuitBreakerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    @DisplayName("연속 Provider 실패 3회 뒤 Circuit을 열고 실제 호출 Permission을 거절한다")
    void recordFailure_opensAfterThreshold() {
        MutableClock clock = new MutableClock(STARTED_AT);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddingProviderCircuitBreaker circuitBreaker = circuitBreaker(clock, meterRegistry);

        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        CallPermission third = circuitBreaker.acquirePermission();
        Duration circuitDelay = circuitBreaker.recordFailure(third, true);

        assertThat(circuitDelay).isEqualTo(Duration.ofSeconds(30));
        assertThat(gauge(meterRegistry)).isEqualTo(1.0);
        assertThat(probeFailedGauge(meterRegistry)).isZero();
        assertThat(transitions(meterRegistry, "open")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "success")).isZero();
        assertThat(probes(meterRegistry, "failed")).isZero();
        assertThatThrownBy(circuitBreaker::acquirePermission)
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_PROVIDER_CIRCUIT_OPEN
            )
            .hasFieldOrPropertyWithValue("minimumRetryDelay", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("정상 호출은 연속 실패 횟수를 초기화한다")
    void recordSuccess_resetsConsecutiveFailures() {
        MutableClock clock = new MutableClock(STARTED_AT);
        EmbeddingProviderCircuitBreaker circuitBreaker = circuitBreaker(clock);

        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordSuccess(circuitBreaker.acquirePermission());
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);

        assertThat(circuitBreaker.acquirePermission().halfOpenProbe()).isFalse();
    }

    @Test
    @DisplayName("Open 시간이 지나면 동시 요청 중 한 건만 Half-open Probe를 획득한다")
    void acquirePermission_allowsSingleHalfOpenProbe() throws Exception {
        MutableClock clock = new MutableClock(STARTED_AT);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddingProviderCircuitBreaker circuitBreaker = openCircuit(clock, meterRegistry);
        clock.advance(Duration.ofSeconds(30));
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> acquire = () -> {
                start.await();
                try {
                    return circuitBreaker.acquirePermission();
                } catch (EmbeddingProviderException exception) {
                    return exception;
                }
            };
            List<Future<Object>> results = List.of(
                executor.submit(acquire),
                executor.submit(acquire)
            );
            start.countDown();

            List<Object> values = results.stream().map(this::get).toList();
            assertThat(values).filteredOn(CallPermission.class::isInstance).hasSize(1);
            assertThat(values).filteredOn(EmbeddingProviderException.class::isInstance).hasSize(1);

            CallPermission probe = values.stream()
                .filter(CallPermission.class::isInstance)
                .map(CallPermission.class::cast)
                .findFirst()
                .orElseThrow();
            assertThat(probe.halfOpenProbe()).isTrue();
            circuitBreaker.recordSuccess(probe);
        } finally {
            executor.shutdownNow();
        }

        assertThat(circuitBreaker.acquirePermission().halfOpenProbe()).isFalse();
        assertThat(gauge(meterRegistry)).isZero();
        assertThat(probeFailedGauge(meterRegistry)).isZero();
        assertThat(transitions(meterRegistry, "half_open")).isEqualTo(1.0);
        assertThat(transitions(meterRegistry, "closed")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "success")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "failed")).isZero();
    }

    @Test
    @DisplayName("Half-open Probe 결과를 기록하지 못하면 소유권을 반환해 다음 Probe를 허용한다")
    void releasePermission_allowsNextHalfOpenProbe() {
        MutableClock clock = new MutableClock(STARTED_AT);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddingProviderCircuitBreaker circuitBreaker = openCircuit(clock, meterRegistry);
        clock.advance(Duration.ofSeconds(30));
        CallPermission abandonedProbe = circuitBreaker.acquirePermission();

        circuitBreaker.releasePermission(abandonedProbe);
        assertThat(probeFailedGauge(meterRegistry)).isZero();
        assertThat(probes(meterRegistry, "success")).isZero();
        assertThat(probes(meterRegistry, "failed")).isZero();

        CallPermission nextProbe = circuitBreaker.acquirePermission();

        assertThat(nextProbe.halfOpenProbe()).isTrue();
        circuitBreaker.recordSuccess(nextProbe);
        assertThat(circuitBreaker.acquirePermission().halfOpenProbe()).isFalse();
        assertThat(probeFailedGauge(meterRegistry)).isZero();
        assertThat(probes(meterRegistry, "success")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "failed")).isZero();
    }

    @Test
    @DisplayName("Half-open Probe가 실패하면 Open 시간을 새로 시작한다")
    void recordFailure_reopensAfterProbeFailure() {
        MutableClock clock = new MutableClock(STARTED_AT);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddingProviderCircuitBreaker circuitBreaker = openCircuit(clock, meterRegistry);
        clock.advance(Duration.ofSeconds(30));

        CallPermission probe = circuitBreaker.acquirePermission();
        assertThat(gauge(meterRegistry)).isEqualTo(1.0);
        assertThat(transitions(meterRegistry, "half_open")).isEqualTo(1.0);

        circuitBreaker.recordFailure(probe, true);

        assertThat(gauge(meterRegistry)).isEqualTo(1.0);
        assertThat(probeFailedGauge(meterRegistry)).isEqualTo(1.0);
        assertThat(transitions(meterRegistry, "open")).isEqualTo(2.0);
        assertThat(probes(meterRegistry, "success")).isZero();
        assertThat(probes(meterRegistry, "failed")).isEqualTo(1.0);
        assertThatThrownBy(circuitBreaker::acquirePermission)
            .isInstanceOf(EmbeddingProviderException.class)
            .hasFieldOrPropertyWithValue("minimumRetryDelay", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("실패한 Half-open Probe 뒤 다음 Probe가 성공하면 실패 Gauge를 해제한다")
    void recordSuccess_clearsFailedProbeGaugeAfterRecovery() {
        MutableClock clock = new MutableClock(STARTED_AT);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EmbeddingProviderCircuitBreaker circuitBreaker = openCircuit(clock, meterRegistry);
        clock.advance(Duration.ofSeconds(30));

        CallPermission failedProbe = circuitBreaker.acquirePermission();
        circuitBreaker.recordFailure(failedProbe, true);

        assertThat(gauge(meterRegistry)).isEqualTo(1.0);
        assertThat(probeFailedGauge(meterRegistry)).isEqualTo(1.0);

        clock.advance(Duration.ofSeconds(30));
        CallPermission successfulProbe = circuitBreaker.acquirePermission();
        circuitBreaker.recordSuccess(successfulProbe);

        assertThat(gauge(meterRegistry)).isZero();
        assertThat(probeFailedGauge(meterRegistry)).isZero();
        assertThat(transitions(meterRegistry, "open")).isEqualTo(2.0);
        assertThat(transitions(meterRegistry, "half_open")).isEqualTo(2.0);
        assertThat(transitions(meterRegistry, "closed")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "failed")).isEqualTo(1.0);
        assertThat(probes(meterRegistry, "success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Open 전 시작한 요청의 늦은 성공은 새 Circuit 상태를 닫지 못한다")
    void staleSuccess_doesNotCloseOpenedCircuit() {
        MutableClock clock = new MutableClock(STARTED_AT);
        EmbeddingProviderCircuitBreaker circuitBreaker = circuitBreaker(clock);
        CallPermission stalePermission = circuitBreaker.acquirePermission();

        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordSuccess(stalePermission);

        assertThatThrownBy(circuitBreaker::acquirePermission)
            .isInstanceOf(EmbeddingProviderException.class);
    }

    @Test
    @DisplayName("Open 전 시작한 요청의 늦은 실패에는 남은 Open 시간을 반환한다")
    void staleFailure_returnsRemainingOpenDelay() {
        MutableClock clock = new MutableClock(STARTED_AT);
        EmbeddingProviderCircuitBreaker circuitBreaker = circuitBreaker(clock);
        CallPermission stalePermission = circuitBreaker.acquirePermission();

        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        clock.advance(Duration.ofSeconds(7));

        assertThat(circuitBreaker.recordFailure(stalePermission, true))
            .isEqualTo(Duration.ofSeconds(23));
    }

    private EmbeddingProviderCircuitBreaker openCircuit(MutableClock clock) {
        return openCircuit(clock, new SimpleMeterRegistry());
    }

    private EmbeddingProviderCircuitBreaker openCircuit(
        MutableClock clock,
        SimpleMeterRegistry meterRegistry
    ) {
        EmbeddingProviderCircuitBreaker circuitBreaker = circuitBreaker(clock, meterRegistry);
        for (int failure = 0; failure < 3; failure++) {
            circuitBreaker.recordFailure(circuitBreaker.acquirePermission(), true);
        }
        return circuitBreaker;
    }

    private EmbeddingProviderCircuitBreaker circuitBreaker(Clock clock) {
        return circuitBreaker(clock, new SimpleMeterRegistry());
    }

    private EmbeddingProviderCircuitBreaker circuitBreaker(
        Clock clock,
        SimpleMeterRegistry meterRegistry
    ) {
        EmbeddingProviderCircuitBreakerProperties properties =
            new EmbeddingProviderCircuitBreakerProperties();
        EmbeddingProviderCircuitMetrics metrics = new EmbeddingProviderCircuitMetrics(meterRegistry);
        return new EmbeddingProviderCircuitBreaker(properties, clock, metrics);
    }

    private double gauge(SimpleMeterRegistry meterRegistry) {
        return meterRegistry.get("docgrid.embedding.provider.circuit.open").gauge().value();
    }

    private double probeFailedGauge(SimpleMeterRegistry meterRegistry) {
        return meterRegistry.get("docgrid.embedding.provider.circuit.probe.failed").gauge().value();
    }

    private double transitions(SimpleMeterRegistry meterRegistry, String state) {
        return meterRegistry.get("docgrid.embedding.provider.circuit.transitions")
            .tag("state", state)
            .counter()
            .count();
    }

    private double probes(SimpleMeterRegistry meterRegistry, String outcome) {
        return meterRegistry.get("docgrid.embedding.provider.circuit.probe")
            .tag("outcome", outcome)
            .counter()
            .count();
    }

    private Object get(Future<Object> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * 실제 Sleep 없이 Circuit Open 경계를 이동시키는 Test 전용 Clock이다.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
