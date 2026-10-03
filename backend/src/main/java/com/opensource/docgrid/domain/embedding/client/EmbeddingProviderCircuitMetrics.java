package com.opensource.docgrid.domain.embedding.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Embedding Provider Circuit의 현재 보호 활성 여부, 상태 전환과 Probe 결과를 Micrometer에
 * 기록한다.
 *
 * <p>Circuit 상태 결정은 하지 않고 {@link EmbeddingProviderCircuitBreaker}가 확정한 전이만 반영한다.
 * 상태 label은 고정된 세 값만 사용해 시계열 Cardinality가 입력 데이터에 따라 증가하지 않게 한다.
 */
@Component
public class EmbeddingProviderCircuitMetrics {

    private static final String TRANSITIONS = "docgrid.embedding.provider.circuit.transitions";
    private static final String PROBES = "docgrid.embedding.provider.circuit.probe";

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger probeFailed = new AtomicInteger();
    private final Counter openTransitions;
    private final Counter halfOpenTransitions;
    private final Counter closedTransitions;
    private final Counter successfulProbes;
    private final Counter failedProbes;

    /** Circuit Gauge와 상태 전환·Probe 결과 Counter를 애플리케이션 시작 시 한 번 등록한다. */
    public EmbeddingProviderCircuitMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("docgrid.embedding.provider.circuit.open", open, AtomicInteger::get)
            .description("Whether the Embedding Provider circuit is open or half-open")
            .register(meterRegistry);
        Gauge.builder(
            "docgrid.embedding.provider.circuit.probe.failed",
            probeFailed,
            AtomicInteger::get
        )
            .description("Whether the current circuit protection episode has a failed probe")
            .register(meterRegistry);
        openTransitions = transitionCounter(meterRegistry, "open");
        halfOpenTransitions = transitionCounter(meterRegistry, "half_open");
        closedTransitions = transitionCounter(meterRegistry, "closed");
        // Counter를 0에서 미리 등록해 첫 Probe 결과도 Prometheus increase()가 관측할 수 있게 한다.
        successfulProbes = probeCounter(meterRegistry, "success");
        failedProbes = probeCounter(meterRegistry, "failed");
    }

    /** Circuit OPEN 전이를 기록하고 현재 차단 상태를 게시한다. */
    void recordOpen() {
        open.set(1);
        openTransitions.increment();
    }

    /** Open 유예 종료 뒤 단일 Probe를 허용하는 HALF_OPEN 전이를 기록한다. */
    void recordHalfOpen() {
        // Probe 성공 전까지 일반 호출은 계속 차단되므로 장기 장애 경보를 끊지 않는다.
        halfOpenTransitions.increment();
    }

    /** Probe 성공으로 정상 호출을 재개한 CLOSED 전이를 기록한다. */
    void recordClosed() {
        open.set(0);
        closedTransitions.increment();
    }

    /** Half-open Probe 성공 횟수를 기록하고 현재 보호 주기의 실패 표시를 해제한다. */
    void recordProbeSuccess() {
        successfulProbes.increment();
        probeFailed.set(0);
    }

    /** Half-open Probe 실패 횟수를 기록하고 현재 보호 주기에 확인된 장애를 표시한다. */
    void recordProbeFailure() {
        failedProbes.increment();
        probeFailed.set(1);
    }

    private Counter transitionCounter(MeterRegistry meterRegistry, String state) {
        return Counter.builder(TRANSITIONS)
            .description("Embedding Provider circuit transitions by destination state")
            .tag("state", state)
            .register(meterRegistry);
    }

    private Counter probeCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(PROBES)
            .description("Embedding Provider half-open probe results by outcome")
            .tag("outcome", outcome)
            .register(meterRegistry);
    }
}
