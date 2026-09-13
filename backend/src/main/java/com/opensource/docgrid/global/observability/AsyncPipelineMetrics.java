package com.opensource.docgrid.global.observability;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 비동기 파이프라인의 커밋된 상태 전이를 Micrometer Counter로 변환한다.
 *
 * <p>도메인 서비스는 DB 상태와 같은 트랜잭션에서 제한된 이벤트만 발행한다. 이 구독자는
 * {@link TransactionPhase#AFTER_COMMIT}에만 실행되므로 롤백된 상태 변화는 Counter에 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AsyncPipelineMetrics {

    private static final String EMBEDDING_ATTEMPTS = "docgrid.embedding.job.attempts";
    private static final String RAG_COMPLETIONS = "docgrid.rag.job.completions";
    private static final String SYNC_ATTEMPTS = "docgrid.sync.event.attempts";

    private final MeterRegistry meterRegistry;

    /**
     * 확정된 Embedding 실행 결과를 실패 분류와 재시도 가능 여부별로 기록한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordEmbeddingAttempt(EmbeddingJobAttemptMetricEvent event) {
        meterRegistry.counter(
            EMBEDDING_ATTEMPTS,
            "outcome", event.outcome().label(),
            "failure_type", event.failureType().name(),
            "retryable", Boolean.toString(event.failureType().isRetryable())
        ).increment();
    }

    /**
     * 확정된 RAG 답변 결과를 정상·fallback·timeout 경로별로 기록한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordRagCompletion(RagJobCompletionMetricEvent event) {
        meterRegistry.counter(
            RAG_COMPLETIONS,
            "outcome", event.outcome().label()
        ).increment();
    }

    /**
     * 확정된 Sync Outbox 처리 결과를 완료·재시도·종결·Lease 회수별로 기록한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recordSyncAttempt(SyncEventAttemptMetricEvent event) {
        meterRegistry.counter(
            SYNC_ATTEMPTS,
            "outcome", event.outcome().label()
        ).increment();
    }
}
