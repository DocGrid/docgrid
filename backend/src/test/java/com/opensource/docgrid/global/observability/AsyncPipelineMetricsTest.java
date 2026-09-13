package com.opensource.docgrid.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.global.observability.RagJobCompletionMetricEvent.Outcome;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 비동기 파이프라인 이벤트가 정해진 이름과 제한된 label의 Counter로 변환되는지 검증한다.
 */
@DisplayName("비동기 파이프라인 상태 전이 Counter 테스트")
class AsyncPipelineMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AsyncPipelineMetrics metrics = new AsyncPipelineMetrics(meterRegistry);

    @Test
    @DisplayName("Embedding 재시도와 사용자 입력 최종 실패를 failure_type과 retryable로 구분한다")
    void recordsEmbeddingOutcomesWithBoundedFailureLabels() {
        metrics.recordEmbeddingAttempt(EmbeddingJobAttemptMetricEvent.failure(
            EmbeddingJobStatus.PENDING,
            IndexingFailureType.EMBEDDING_PROVIDER_TIMEOUT
        ));
        metrics.recordEmbeddingAttempt(EmbeddingJobAttemptMetricEvent.failure(
            EmbeddingJobStatus.FAILED,
            IndexingFailureType.DOCUMENT_CONTENT_INVALID
        ));

        assertThat(embeddingCounter("retry_scheduled", "EMBEDDING_PROVIDER_TIMEOUT", "true"))
            .isEqualTo(1.0);
        assertThat(embeddingCounter("terminal_failure", "DOCUMENT_CONTENT_INVALID", "false"))
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("RAG fallback과 timeout을 서로 다른 완료 결과로 기록한다")
    void recordsRagCompletionPathsSeparately() {
        metrics.recordRagCompletion(new RagJobCompletionMetricEvent(Outcome.PROVIDER_FALLBACK));
        metrics.recordRagCompletion(new RagJobCompletionMetricEvent(Outcome.TIMEOUT_SWEPT));

        assertThat(ragCounter("provider_fallback")).isEqualTo(1.0);
        assertThat(ragCounter("timeout_swept")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Outbox 재시도와 최종 실패를 서로 다른 처리 결과로 기록한다")
    void recordsSyncAttemptOutcomesSeparately() {
        metrics.recordSyncAttempt(new SyncEventAttemptMetricEvent(
            SyncEventAttemptMetricEvent.Outcome.RETRY_SCHEDULED
        ));
        metrics.recordSyncAttempt(new SyncEventAttemptMetricEvent(
            SyncEventAttemptMetricEvent.Outcome.TERMINAL_FAILURE
        ));

        assertThat(syncCounter("retry_scheduled")).isEqualTo(1.0);
        assertThat(syncCounter("terminal_failure")).isEqualTo(1.0);
    }

    private double embeddingCounter(String outcome, String failureType, String retryable) {
        return meterRegistry.get("docgrid.embedding.job.attempts")
            .tag("outcome", outcome)
            .tag("failure_type", failureType)
            .tag("retryable", retryable)
            .counter()
            .count();
    }

    private double ragCounter(String outcome) {
        return meterRegistry.get("docgrid.rag.job.completions")
            .tag("outcome", outcome)
            .counter()
            .count();
    }

    private double syncCounter(String outcome) {
        return meterRegistry.get("docgrid.sync.event.attempts")
            .tag("outcome", outcome)
            .counter()
            .count();
    }
}
