package com.opensource.docgrid.global.observability;

import java.util.Objects;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;

/**
 * 커밋이 확정된 Embedding Job 실행 결과를 제한된 Micrometer label 값으로 전달한다.
 *
 * <p>Job ID나 오류 메시지를 싣지 않아 시계열 cardinality가 입력 데이터에 따라 증가하지 않는다.
 */
public record EmbeddingJobAttemptMetricEvent(
    Outcome outcome,
    FailureType failureType,
    boolean retryable
) {

    /**
     * 성공한 실행의 단일 label 조합을 생성한다.
     */
    public static EmbeddingJobAttemptMetricEvent success() {
        return new EmbeddingJobAttemptMetricEvent(Outcome.SUCCESS, FailureType.NONE, false);
    }

    /**
     * Worker가 보고한 고정 실패 분류와 전이 후 상태로 재시도 또는 최종 실패를 구분한다.
     */
    public static EmbeddingJobAttemptMetricEvent failure(
        EmbeddingJobStatus status,
        IndexingFailureType failureType
    ) {
        IndexingFailureType requiredFailureType = Objects.requireNonNull(
            failureType,
            "failureType은 필수입니다."
        );
        return new EmbeddingJobAttemptMetricEvent(
            Outcome.fromFailureStatus(status),
            FailureType.from(requiredFailureType),
            requiredFailureType.isRetryable()
        );
    }

    /**
     * Worker Lease 만료는 외부 요청 enum과 분리된 고정 운영 실패 분류로 기록한다.
     */
    public static EmbeddingJobAttemptMetricEvent leaseExpired(EmbeddingJobStatus status) {
        return new EmbeddingJobAttemptMetricEvent(
            Outcome.fromFailureStatus(status),
            FailureType.WORKER_LEASE_EXPIRED,
            true
        );
    }

    /**
     * 성공과 실패 label 조합이 서로 모순되지 않도록 생성 시점에 검증한다.
     */
    public EmbeddingJobAttemptMetricEvent {
        Objects.requireNonNull(outcome, "outcome은 필수입니다.");
        Objects.requireNonNull(failureType, "failureType은 필수입니다.");
        if ((outcome == Outcome.SUCCESS) != (failureType == FailureType.NONE)) {
            throw new IllegalArgumentException("성공 결과만 NONE 실패 유형을 사용할 수 있습니다.");
        }
        if (outcome == Outcome.SUCCESS && retryable) {
            throw new IllegalArgumentException("성공 결과는 retryable일 수 없습니다.");
        }
    }

    /**
     * Embedding 실행이 확정된 뒤 노출할 저 cardinality 결과 label이다.
     */
    public enum Outcome {
        SUCCESS("success"),
        RETRY_SCHEDULED("retry_scheduled"),
        TERMINAL_FAILURE("terminal_failure");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static Outcome fromFailureStatus(EmbeddingJobStatus status) {
            return switch (Objects.requireNonNull(status, "status는 필수입니다.")) {
                case PENDING -> RETRY_SCHEDULED;
                case FAILED -> TERMINAL_FAILURE;
                default -> throw new IllegalArgumentException("실패 전이 후 상태는 PENDING 또는 FAILED여야 합니다.");
            };
        }
    }

    /**
     * 외부 요청 enum과 내부 Lease 회수 원인을 합친 고정 실패 label 목록이다.
     */
    public enum FailureType {
        NONE,
        STORAGE_UNAVAILABLE,
        STORAGE_CONFIGURATION_INVALID,
        STORAGE_OBJECT_MISSING,
        DOCUMENT_CONTENT_INVALID,
        EMBEDDING_PROVIDER_UNAVAILABLE,
        EMBEDDING_PROVIDER_OVERLOADED,
        EMBEDDING_PROVIDER_TIMEOUT,
        EMBEDDING_PROVIDER_CIRCUIT_OPEN,
        EMBEDDING_REQUEST_INVALID,
        EMBEDDING_RESULT_INVALID,
        INDEXING_STATE_INCONSISTENT,
        WORKER_INTERNAL_ERROR,
        WORKER_LEASE_EXPIRED;

        private static FailureType from(IndexingFailureType failureType) {
            // 도메인 enum이 늘어나면 이 switch가 컴파일 오류를 내므로 런타임 실패 경로를 막는다.
            return switch (failureType) {
                case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
                case STORAGE_CONFIGURATION_INVALID -> STORAGE_CONFIGURATION_INVALID;
                case STORAGE_OBJECT_MISSING -> STORAGE_OBJECT_MISSING;
                case DOCUMENT_CONTENT_INVALID -> DOCUMENT_CONTENT_INVALID;
                case EMBEDDING_PROVIDER_UNAVAILABLE -> EMBEDDING_PROVIDER_UNAVAILABLE;
                case EMBEDDING_PROVIDER_OVERLOADED -> EMBEDDING_PROVIDER_OVERLOADED;
                case EMBEDDING_PROVIDER_TIMEOUT -> EMBEDDING_PROVIDER_TIMEOUT;
                case EMBEDDING_PROVIDER_CIRCUIT_OPEN -> EMBEDDING_PROVIDER_CIRCUIT_OPEN;
                case EMBEDDING_REQUEST_INVALID -> EMBEDDING_REQUEST_INVALID;
                case EMBEDDING_RESULT_INVALID -> EMBEDDING_RESULT_INVALID;
                case INDEXING_STATE_INCONSISTENT -> INDEXING_STATE_INCONSISTENT;
                case WORKER_INTERNAL_ERROR -> WORKER_INTERNAL_ERROR;
            };
        }
    }
}
