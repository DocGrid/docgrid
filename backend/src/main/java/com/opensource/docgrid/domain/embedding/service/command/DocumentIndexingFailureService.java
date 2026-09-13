package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobAttemptConverter;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.EmbeddingJobAttemptMetricEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 유효한 Worker Claim의 협력적 인덱싱 실패 요청과 멱등 재생을 처리하는 Command Service.
 *
 * <p>Job을 가장 먼저 잠가 완료·실패·복구 경쟁을 직렬화하고 Worker, Claim Token, Lease와 Attempt
 * 실행 Context를 검증한다. Retry, Version·Document 최종 실패와 Event 저장은
 * {@link IndexingFailureTransitionService}에 위임해 다른 실패 발생 경로와 같은 정책을 사용한다.
 */
@Slf4j
@Service
@Transactional
public class DocumentIndexingFailureService {

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final EmbeddingJobAttemptConverter attemptConverter;
    private final IndexingFailureTransitionService failureTransitionService;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 운영 경로에서 공통 실패 전이 Service를 주입받는 생성자.
     */
    @Autowired
    public DocumentIndexingFailureService(
        EmbeddingJobRepository embeddingJobRepository,
        EmbeddingJobAttemptRepository embeddingJobAttemptRepository,
        EmbeddingJobOwnershipValidator ownershipValidator,
        EmbeddingJobAttemptConverter attemptConverter,
        IndexingFailureTransitionService failureTransitionService,
        Clock clock,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.embeddingJobRepository = embeddingJobRepository;
        this.embeddingJobAttemptRepository = embeddingJobAttemptRepository;
        this.ownershipValidator = ownershipValidator;
        this.attemptConverter = attemptConverter;
        this.failureTransitionService = failureTransitionService;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 기존 단위 테스트가 실제 공통 전이 구현을 사용하도록 Repository 기반으로 조립하는 생성자.
     */
    DocumentIndexingFailureService(
        EmbeddingJobRepository embeddingJobRepository,
        EmbeddingJobAttemptRepository embeddingJobAttemptRepository,
        DocumentVersionRepository documentVersionRepository,
        DocumentRepository documentRepository,
        EmbeddingRepository embeddingRepository,
        IndexingEventRepository indexingEventRepository,
        EmbeddingJobOwnershipValidator ownershipValidator,
        EmbeddingJobAttemptConverter attemptConverter,
        IndexingWorkerProperties workerProperties,
        Clock clock,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            ownershipValidator,
            attemptConverter,
            new IndexingFailureTransitionService(
                documentVersionRepository,
                documentRepository,
                embeddingRepository,
                indexingEventRepository,
                new IndexingRetryDelayPolicy(workerProperties)
            ),
            clock,
            applicationEventPublisher
        );
    }

    /**
     * 현재 Attempt를 실패로 종결하고 서버 정책에 따라 Job을 재예약하거나 최종 종료한다.
     */
    public DocumentIndexingFailureResponse fail(
        Long jobId,
        Long attemptId,
        FailDocumentIndexingRequest request
    ) {
        return fail(jobId, attemptId, request, Duration.ZERO);
    }

    /**
     * 내부 Worker가 Provider의 안전한 최소 Retry 지연을 포함해 현재 Attempt 실패를 기록한다.
     */
    public DocumentIndexingFailureResponse fail(
        Long jobId,
        Long attemptId,
        FailDocumentIndexingRequest request,
        Duration minimumRetryDelay
    ) {
        if (minimumRetryDelay == null || minimumRetryDelay.isNegative()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        // 1. Claim 세대 교체와 완료·실패 경쟁을 Job 행에서 직렬화하고 요청 Attempt를 먼저 확인한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        EmbeddingJobAttempt attempt = findAttempt(embeddingJob, request.claimToken());

        // 2. 이미 실패한 같은 실행은 현재 Job과 Lease가 바뀌었어도 저장된 최초 결과만 재생한다.
        if (attempt.getStatus() == AttemptStatus.FAILED) {
            validateFailureReplay(embeddingJob, attempt, attemptId, request);
            return attemptConverter.toFailureResponse(attempt);
        }
        if (attempt.getStatus() != AttemptStatus.STARTED) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT);
        }
        if (embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING);
        }

        // PostgreSQL TIMESTAMP 정밀도와 맞춰 최초 실패와 DB 재생 응답의 시각을 동일하게 유지한다.
        LocalDateTime failedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);

        // 3. 현재 소유권과 STARTED Attempt를 검증하고 공통 전이에 원자 상태 변경을 위임한다.
        ownershipValidator.validate(
            embeddingJob,
            request.workerId(),
            request.claimToken(),
            failedAt
        );
        validateStartedAttempt(embeddingJob, attempt, attemptId, request, failedAt);
        failureTransitionService.transition(
            embeddingJob,
            Optional.of(attempt),
            request.failureType().name(),
            request.errorMessage(),
            request.failureType().isRetryable(),
            failedAt,
            minimumRetryDelay
        );

        // 4. 대시보드 갱신과 실패 Counter를 같은 Commit에 결박한다. transition()이 재시도 예약
        //    (PENDING)과 최종 실패(FAILED) 중 어느 쪽으로 끝났든 embeddingJob은 같은 영속 인스턴스라
        //    최종 상태를 그대로 반영한다. AFTER_COMMIT 구독자만 반응하므로 이 Transaction이 실제로
        //    커밋된 뒤에만 push와 Counter 증가로 이어진다.
        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));
        applicationEventPublisher.publishEvent(EmbeddingJobAttemptMetricEvent.failure(
            embeddingJob.getStatus(),
            request.failureType()
        ));

        log.info(
            "문서 인덱싱 실패 기록: jobId={}, attemptId={}, failureType={}, retryCount={}, terminal={}",
            embeddingJob.getId(),
            attempt.getId(),
            request.failureType(),
            embeddingJob.getRetryCount(),
            embeddingJob.getStatus() == EmbeddingJobStatus.FAILED
        );
        return attemptConverter.toFailureResponse(attempt);
    }

    /**
     * 완료·다른 실패·Lease 복구와의 경쟁을 직렬화하도록 Job을 비관적 잠금으로 조회한다.
     */
    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    /**
     * Job과 Claim Token에 대응하는 Attempt를 조회해 현재 또는 멱등 재생 실행을 식별한다.
     */
    private EmbeddingJobAttempt findAttempt(EmbeddingJob embeddingJob, String claimToken) {
        return embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));
    }

    /**
     * 이미 실패한 Attempt의 재요청이 최초 요청과 같은 실행 식별자·오류 내용인지 검증한다.
     *
     * <p>Job은 첫 실패 후 PENDING 또는 FAILED로 바뀔 수 있으므로 현재 Lease를 다시 요구하지 않는다.
     * 대신 저장된 Attempt 종료 시각과 duration까지 확인해 손상되지 않은 최초 결과만 재생한다.
     */
    private void validateFailureReplay(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        Long attemptId,
        FailDocumentIndexingRequest request
    ) {
        boolean identityMatches = Objects.equals(attempt.getId(), attemptId)
            && attempt.getEmbeddingJob() != null
            && Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            && attempt.getWorkerNode() != null
            && Objects.equals(attempt.getWorkerNode().getId(), request.workerId())
            && Objects.equals(attempt.getClaimToken(), request.claimToken());
        if (!identityMatches) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }

        boolean failureMatches = Objects.equals(attempt.getErrorCode(), request.failureType().name())
            && Objects.equals(attempt.getErrorMessage(), request.errorMessage());
        if (!failureMatches) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT);
        }
        if (attempt.getEndedAt() == null
            || attempt.getDurationMs() == null
            || attempt.getDurationMs() < 0
            || attempt.getStartedAt() == null
            || attempt.getStartedAt().isAfter(attempt.getEndedAt())
            || Duration.between(attempt.getStartedAt(), attempt.getEndedAt()).toMillis()
                != attempt.getDurationMs()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    /**
     * 실패 요청이 현재 Job의 동일 Worker·Token으로 시작된 Attempt인지 확인한다.
     *
     * <p>Attempt 시작 시각이 실패 기준 시각보다 뒤면 처리 시간을 신뢰할 수 없으므로 정합성 오류로 중단한다.
     */
    private void validateStartedAttempt(
        EmbeddingJob embeddingJob,
        EmbeddingJobAttempt attempt,
        Long attemptId,
        FailDocumentIndexingRequest request,
        LocalDateTime failedAt
    ) {
        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getEmbeddingJob() == null
            || !Objects.equals(attempt.getEmbeddingJob().getId(), embeddingJob.getId())
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), request.workerId())
            || !Objects.equals(attempt.getClaimToken(), request.claimToken())
            || attempt.getStartedAt() == null) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
        if (attempt.getStartedAt().isAfter(failedAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }
}
