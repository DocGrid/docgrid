package com.opensource.docgrid.domain.embedding.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Embedding Provider Circuit의 현재 보호 활성 여부와 상태 전환 횟수를 Micrometer에 기록한다.
 *
 * <p>Circuit 상태 결정은 하지 않고 {@link EmbeddingProviderCircuitBreaker}가 확정한 전이만 반영한다.
 * 상태 label은 고정된 세 값만 사용해 시계열 Cardinality가 입력 데이터에 따라 증가하지 않게 한다.
 */
@Component
public class EmbeddingProviderCircuitMetrics {

    private static final String TRANSITIONS = "docgrid.embedding.provider.circuit.transitions";

    private final AtomicInteger open = new AtomicInteger();
    private final Counter openTransitions;
    private final Counter halfOpenTransitions;
    private final Counter closedTransitions;

    /** Circuit Gauge와 상태별 전환 Counter를 애플리케이션 시작 시 한 번 등록한다. */
    public EmbeddingProviderCircuitMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("docgrid.embedding.provider.circuit.open", open, AtomicInteger::get)
            .description("Whether the Embedding Provider circuit is open or half-open")
            .register(meterRegistry);
        openTransitions = transitionCounter(meterRegistry, "open");
        halfOpenTransitions = transitionCounter(meterRegistry, "half_open");
        closedTransitions = transitionCounter(meterRegistry, "closed");
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

    private Counter transitionCounter(MeterRegistry meterRegistry, String state) {
        return Counter.builder(TRANSITIONS)
            .description("Embedding Provider circuit transitions by destination state")
            .tag("state", state)
            .register(meterRegistry);
    }
}
