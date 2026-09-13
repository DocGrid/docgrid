package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.EmbeddingJobAttemptMetricEvent;

import lombok.RequiredArgsConstructor;

/**
 * 만료된 Embedding Job Lease 후보 한 건을 독립 Transaction에서 재검증하고 복구하는 Command Service.
 *
 * <p>Job 행 잠금이 복구, 갱신, 완료와 협력적 실패의 단일 직렬화 지점이다. 현재 Claim의 STARTED
 * Attempt가 있으면 함께 실패시키고, 없으면 가짜 실행 이력을 만들지 않는다. Retry와 최종 실패 정책은
 * {@link IndexingFailureTransitionService}에 위임하며 외부 I/O를 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingJobLeaseRecoveryService {

    private static final String LEASE_EXPIRED_FAILURE_CODE = "WORKER_LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE =
        "Embedding Job Lease가 만료되어 현재 실행을 회수했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final IndexingFailureTransitionService failureTransitionService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 후보 Snapshot 이후에도 만료 상태인 Job만 회수해 Retry 또는 최종 실패로 전환한다.
     *
     * @param jobId 복구 후보 Embedding Job 식별자
     * @param recoveredAt 이번 Scheduler 실행의 복구 기준 시각
     * @return 실제 복구 여부와 복구 후 Job 상태
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryResult recover(Long jobId, LocalDateTime recoveredAt) {
        validateRequest(jobId, recoveredAt);

        // 1. 갱신·완료·실패와 경쟁하는 Job 행을 기다리지 않고 잠근 뒤 만료 조건을 다시 검증한다.
        Optional<EmbeddingJob> candidate = embeddingJobRepository
            .findExpiredByIdForUpdateSkipLocked(jobId, recoveredAt);
        if (candidate.isEmpty()) {
            return RecoveryResult.skipped(jobId);
        }
        EmbeddingJob embeddingJob = candidate.get();
        validateExpiredOwnership(embeddingJob, jobId, recoveredAt);

        // 2. 현재 Claim Token으로 실제 시작된 Attempt만 조회하고 Job·Worker·Token 실행 Context를 검증한다.
        Optional<EmbeddingJobAttempt> attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), embeddingJob.getClaimToken());
        attempt.ifPresent(currentAttempt ->
            validateStartedAttempt(embeddingJob, currentAttempt, recoveredAt)
        );

        // 3. Token을 제외한 회수 원인 Event를 상태 전이 전 Snapshot으로 같은 Transaction에 기록한다.
        saveLeaseExpiredEvent(embeddingJob, attempt, recoveredAt);

        // 4. 기존 실패 정책으로 Attempt 종료, Retry 예약 또는 검색 상태의 최종 실패를 원자적으로 적용한다.
        failureTransitionService.transition(
            embeddingJob,
            attempt,
            LEASE_EXPIRED_FAILURE_CODE,
            LEASE_EXPIRED_MESSAGE,
            true,
            recoveredAt,
            Duration.ZERO
        );

        // 5. 실제 회수 전이가 커밋된 뒤에만 재시도 또는 최종 실패 Counter가 증가하게 한다.
        applicationEventPublisher.publishEvent(
            EmbeddingJobAttemptMetricEvent.leaseExpired(embeddingJob.getStatus())
        );
        return RecoveryResult.recovered(embeddingJob);
    }

    /**
     * Scheduler가 전달한 복구 대상 ID와 기준 시각의 최소 형식을 검증한다.
     */
    private void validateRequest(Long jobId, LocalDateTime recoveredAt) {
        if (jobId == null || jobId <= 0 || recoveredAt == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    /**
     * 잠근 Job이 여전히 동일한 PROCESSING Claim이며 기준 시각에 실제로 만료됐는지 검증한다.
     *
     * <p>Worker, canonical UUID Token, 잠금 시각 범위와 처리 버전 참조까지 확인해 손상된 소유권
     * 데이터를 단순 Retry로 숨기지 않는다.
     */
    private void validateExpiredOwnership(
        EmbeddingJob embeddingJob,
        Long jobId,
        LocalDateTime recoveredAt
    ) {
        if (embeddingJob.getId() == null
            || !Objects.equals(embeddingJob.getId(), jobId)
            || embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING
            || embeddingJob.getLockedByWorker() == null
            || embeddingJob.getLockedByWorker().getId() == null
            || !isCanonicalUuid(embeddingJob.getClaimToken())
            || embeddingJob.getLockedAt() == null
            || embeddingJob.getLockExpiresAt() == null
            || !embeddingJob.getLockExpiresAt().isAfter(embeddingJob.getLockedAt())
            || embeddingJob.getLockExpiresAt().isAfter(recoveredAt)
            || embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    /**
     * Claim Token이 소문자 표준 UUID 문자열 형식인지 확인한다.
     */
    private boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 만료 Claim으로 시작된 Attempt가 Job의 Worker·Token과 일치하고 아직 STARTED인지 확인한다.
     */
    private void validateStartedAttempt(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        LocalDateTime recoveredAt
    ) {
        if (attempt.getId() == null
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(
                attempt.getWorkerNode().getId(),
                embeddingJob.getLockedByWorker().getId()
            )
            || !Objects.equals(attempt.getClaimToken(), embeddingJob.getClaimToken())
            || attempt.getAttemptNo() <= 0
            || attempt.getStatus() != AttemptStatus.STARTED
            || attempt.getStartedAt() == null
            || attempt.getStartedAt().isAfter(recoveredAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    /**
     * 실패 전이 전에 만료 당시 Worker·Attempt·Retry Snapshot을 LEASE_EXPIRED 이벤트로 저장한다.
     */
    private void saveLeaseExpiredEvent(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        LocalDateTime recoveredAt
    ) {
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.LEASE_EXPIRED)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.PROCESSING.name())
            .message(LEASE_EXPIRED_MESSAGE)
            .metadataJson(leaseExpiredMetadata(embeddingJob, attempt))
            .occurredAt(recoveredAt)
            .build());
    }

    /**
     * Claim Token을 제외한 Lease 만료 진단 정보를 이벤트 Metadata JSON으로 생성한다.
     *
     * <p>Attempt가 시작되기 전에 Worker가 멈춘 경우에는 존재하지 않는 Attempt 정보를 만들지 않는다.
     */
    private String leaseExpiredMetadata(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt
    ) {
        return attempt
            .map(currentAttempt ->
                """
                    {"workerId":%d,"attemptId":%d,"attemptNo":%d,"expiredAt":"%s","retryCountBefore":%d}
                    """.formatted(
                        embeddingJob.getLockedByWorker().getId(),
                        currentAttempt.getId(),
                        currentAttempt.getAttemptNo(),
                        embeddingJob.getLockExpiresAt(),
                        embeddingJob.getRetryCount()
                    ).strip()
            )
            .orElseGet(() ->
                """
                    {"workerId":%d,"expiredAt":"%s","retryCountBefore":%d}
                    """.formatted(
                        embeddingJob.getLockedByWorker().getId(),
                        embeddingJob.getLockExpiresAt(),
                        embeddingJob.getRetryCount()
                    ).strip()
            );
    }

    /**
     * 복구 후보 한 건이 실제 상태 전이를 수행했는지와 전이 후 Job 상태를 전달하는 내부 결과.
     *
     * <p>Scheduler 집계에만 사용하며 Entity와 Claim Token을 외부로 노출하지 않는다.
     */
    public record RecoveryResult(
        Long jobId,
        boolean recovered,
        EmbeddingJobStatus status
    ) {

        /**
         * 잠금 경쟁 또는 갱신으로 더 이상 만료되지 않은 후보의 건너뜀 결과를 생성한다.
         */
        private static RecoveryResult skipped(Long jobId) {
            return new RecoveryResult(jobId, false, null);
        }

        /**
         * 실제 실패 전이를 수행한 Job의 최종 상태를 복구 결과로 생성한다.
         */
        private static RecoveryResult recovered(EmbeddingJob embeddingJob) {
            return new RecoveryResult(embeddingJob.getId(), true, embeddingJob.getStatus());
        }
    }
}
