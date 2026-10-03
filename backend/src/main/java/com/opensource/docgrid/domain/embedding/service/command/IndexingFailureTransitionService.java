package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 잠긴 Embedding Job의 Retry 또는 최종 실패 상태 전이를 공통으로 적용하는 내부 Command Service.
 *
 * <p>호출자는 Job 소유권이나 Lease 같은 실패 발생 경로별 조건을 먼저 검증하고 Job 행 잠금을 유지해야
 * 한다. 이 Service는 Version → Document 순서의 후속 잠금, 선택적인 STARTED Attempt 종료, Queue 복귀
 * 또는 검색 가용성 최종 실패와 Event 저장만 책임진다. 외부 I/O와 독립 Transaction은 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class IndexingFailureTransitionService {

    private static final String PARSE_FAILURE_MESSAGE = "Document Version 파싱 단계가 실패했습니다.";
    private static final String EMBEDDING_FAILURE_MESSAGE = "Document Version 임베딩 단계가 실패했습니다.";
    private static final String RETRY_MESSAGE = "Embedding Job 재시도를 예약했습니다.";
    private static final String TERMINAL_FAILURE_MESSAGE = "Embedding Job을 최종 실패로 종료했습니다.";

    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final IndexingRetryDelayPolicy retryDelayPolicy;

    /**
     * 실패 원인과 Retry 가능 정책을 현재 Job, Attempt, Version, Document에 원자적으로 반영한다.
     *
     * @param embeddingJob 호출 Transaction이 이미 쓰기 잠근 PROCESSING Job
     * @param attempt 현재 Claim의 STARTED Attempt, Attempt 시작 전 장애면 빈 값
     * @param failureCode 저장할 제한된 실패 코드
     * @param failureMessage 저장할 안전한 진단 메시지
     * @param retryable 재시도 가능 실패인지 여부
     * @param failedAt 모든 상태와 Event가 공유할 실패 시각
     * @param minimumRetryDelay Provider가 요청한 안전한 최소 재실행 지연
     */
    public void transition(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        String failureCode,
        String failureMessage,
        boolean retryable,
        LocalDateTime failedAt,
        Duration minimumRetryDelay
    ) {
        // 1. 호출 경로가 보장해야 하는 현재 Job과 실패 입력의 최소 불변식을 다시 확인한다.
        validateTransitionInput(
            embeddingJob,
            attempt,
            failureCode,
            failureMessage,
            failedAt,
            minimumRetryDelay
        );

        // 2. 완료·협력적 실패·Lease 복구가 같은 Job → Version → Document 잠금 순서를 사용한다.
        DocumentVersion documentVersion = findLockedVersion(embeddingJob);
        Document document = findLockedDocument(documentVersion);
        validateFailureTarget(embeddingJob, documentVersion, document);

        // 3. 실제 실행 Attempt가 시작된 경우에만 최초 실패 결과를 종료 이력에 기록한다.
        attempt.ifPresent(currentAttempt ->
            markAttemptFailed(currentAttempt, failureCode, failureMessage, failedAt)
        );

        // 4. Retry 가능 정책과 남은 횟수에 따라 Queue 복귀 또는 검색 상태 최종 실패를 함께 적용한다.
        DocumentVersionStatus failedFromStatus = documentVersion.getStatus();
        IndexingEventType stageEventType = resolveStageEventType(failedFromStatus);
        if (retryable && embeddingJob.hasRemainingRetries()) {
            scheduleRetry(
                embeddingJob,
                attempt,
                failedFromStatus,
                stageEventType,
                failureCode,
                failureMessage,
                failedAt,
                minimumRetryDelay
            );
            return;
        }
        terminateFailure(
            embeddingJob,
            attempt,
            documentVersion,
            document,
            failedFromStatus,
            stageEventType,
            failureCode,
            failureMessage,
            failedAt
        );
    }

    /**
     * 공통 실패 전이를 시작하기 위한 Job 상태와 오류 정보의 최소 계약을 검증한다.
     *
     * <p>호출 경로에 따라 Attempt가 없을 수 있지만 Optional 자체는 항상 전달되어야 한다.
     * 재시도 최소 지연은 현재 시각보다 앞선 예약을 만들지 않도록 음수를 허용하지 않는다.
     */
    private void validateTransitionInput(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        String failureCode,
        String failureMessage,
        LocalDateTime failedAt,
        Duration minimumRetryDelay
    ) {
        if (embeddingJob == null
            || embeddingJob.getStatus() != EmbeddingJobStatus.PROCESSING
            || attempt == null
            || failureCode == null
            || failureCode.isBlank()
            || failureMessage == null
            || failureMessage.isBlank()
            || failedAt == null
            || minimumRetryDelay == null
            || minimumRetryDelay.isNegative()) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
    }

    /**
     * Job이 직접 참조하는 처리 버전을 비관적 잠금으로 조회한다.
     */
    private DocumentVersion findLockedVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
    }

    /**
     * 버전의 문서 원장을 잠금 조회해 실패 시 문서 검색 상태 변경을 직렬화한다.
     */
    private Document findLockedDocument(DocumentVersion documentVersion) {
        if (documentVersion.getDocument() == null
            || documentVersion.getDocument().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        return documentRepository.findByIdForUpdate(documentVersion.getDocument().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT));
    }

    /**
     * Job·Version·Document 관계가 일치하고 현재 버전 상태가 실패 전이를 허용하는지 확인한다.
     */
    private void validateFailureTarget(
        EmbeddingJob embeddingJob,
        DocumentVersion documentVersion,
        Document document
    ) {
        if (!Objects.equals(embeddingJob.getDocumentVersion().getId(), documentVersion.getId())
            || documentVersion.getDocument() == null
            || !Objects.equals(documentVersion.getDocument().getId(), document.getId())
            || document.getDeletedAt() != null) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        resolveStageEventType(documentVersion.getStatus());
    }

    /**
     * 시작된 Attempt를 실패 시각과 실행 시간, 제한된 오류 정보로 종결한다.
     */
    private void markAttemptFailed(
        EmbeddingJobAttempt attempt,
        String failureCode,
        String failureMessage,
        LocalDateTime failedAt
    ) {
        if (attempt.getStartedAt() == null || attempt.getStartedAt().isAfter(failedAt)) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }
        long durationMs = Duration.between(attempt.getStartedAt(), failedAt).toMillis();
        attempt.markFailed(failedAt, durationMs, failureCode, failureMessage);
    }

    /**
     * 실패 당시 Version 상태를 파싱 또는 임베딩 단계 실패 이벤트로 분류한다.
     *
     * <p>완료·최종 실패처럼 처리 단계 밖의 상태는 이 전이의 입력이 될 수 없다.
     */
    private IndexingEventType resolveStageEventType(DocumentVersionStatus versionStatus) {
        return switch (versionStatus) {
            case UPLOADED, PARSING -> IndexingEventType.PARSE_FAILED;
            case CHUNKED, EMBEDDING -> IndexingEventType.EMBEDDING_FAILED;
            default -> throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        };
    }

    /**
     * Version의 재개 지점을 유지한 채 Job을 지연된 PENDING 상태로 되돌리고 실패·재시도 이벤트를 남긴다.
     *
     * <p>Attempt가 시작됐다면 단계 실패 이벤트에 실행 식별자를 포함하고, Provider가 제시한 최소
     * 지연과 지수 Backoff 중 안전한 값을 다음 실행 시각에 반영한다. PostgreSQL TIMESTAMP 저장 전
     * 마이크로초 단위로 올려 Provider 최소 시각보다 이른 예약을 막는다.
     */
    private void scheduleRetry(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        DocumentVersionStatus versionStatus,
        IndexingEventType stageEventType,
        String failureCode,
        String failureMessage,
        LocalDateTime failedAt,
        Duration minimumRetryDelay
    ) {
        Duration retryDelay = retryDelayPolicy.calculate(
            embeddingJob.getRetryCount(),
            minimumRetryDelay
        );
        LocalDateTime nextRetryAt = failedAt.plus(retryDelay);
        // 1. 나노초가 DB 정밀도에서 사라져 Provider 하한을 위반하지 않도록 저장 시각을 올림한다.
        int nanosBeyondMicros = nextRetryAt.getNano() % 1_000;
        if (nanosBeyondMicros != 0) {
            nextRetryAt = nextRetryAt.plusNanos(1_000 - nanosBeyondMicros);
        }
        embeddingJob.scheduleRetry(failureCode, failureMessage, nextRetryAt);

        // 2. Version 상태는 재개 지점으로 보존한 채 현재 처리 단계의 실패 이벤트를 기록한다.
        saveStageFailureEvent(
            embeddingJob,
            attempt,
            stageEventType,
            versionStatus.name(),
            versionStatus.name(),
            failureCode,
            failedAt
        );

        // 3. 계산된 재실행 시각과 증가한 Retry 횟수를 Queue 재예약 이벤트에 남긴다.
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.RETRY)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.PENDING.name())
            .message(RETRY_MESSAGE)
            .metadataJson(retryMetadata(embeddingJob, nextRetryAt))
            .occurredAt(failedAt)
            .build());
    }

    /**
     * 재시도할 수 없는 실행의 Vector·Version·Document·Job을 최종 실패 상태로 일관되게 종결한다.
     */
    private void terminateFailure(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        DocumentVersion documentVersion,
        Document document,
        DocumentVersionStatus versionStatus,
        IndexingEventType stageEventType,
        String failureCode,
        String failureMessage,
        LocalDateTime failedAt
    ) {
        // 1. 대상 Version의 검색 가능 Set을 비활성화하고 처리 중 상태를 최종 실패로 종결한다.
        embeddingRepository.markActiveAsStaleByDocumentVersionId(documentVersion.getId());
        documentVersion.markFailed();
        transitionDocumentOnTerminalFailure(document, documentVersion);
        embeddingJob.markFailed(failureCode, failureMessage, failedAt);

        // 2. 단계 실패와 Job 최종 실패 이벤트를 같은 Transaction과 시각에 append한다.
        saveStageFailureEvent(
            embeddingJob,
            attempt,
            stageEventType,
            versionStatus.name(),
            DocumentVersionStatus.FAILED.name(),
            failureCode,
            failedAt
        );
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.FAILED)
            .fromStatus(EmbeddingJobStatus.PROCESSING.name())
            .toStatus(EmbeddingJobStatus.FAILED.name())
            .message(TERMINAL_FAILURE_MESSAGE)
            .metadataJson(terminalFailureMetadata(embeddingJob))
            .occurredAt(failedAt)
            .build());
    }

    /**
     * 실패한 버전이 문서의 검색 가용성에 미치는 범위를 판단해 문서 상태를 전이한다.
     *
     * <p>재인덱싱 실패이고 이전 INDEXED currentVersion이 남아 있으면 문서는 계속 검색 가능하므로
     * INDEXED 상태와 포인터를 유지한다. 검색 가능한 버전이 없는 최초 처리 실패만 문서를 FAILED로 바꾼다.
     */
    private void transitionDocumentOnTerminalFailure(
        Document document,
        DocumentVersion failedVersion
    ) {
        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null
            || currentVersion.getId() == null
            || currentVersion.getDocument() == null
            || !Objects.equals(currentVersion.getDocument().getId(), document.getId())) {
            throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
        }

        // 이전 INDEXED Version이 있으면 검색 가용성과 현재 포인터를 그대로 보존한다.
        if (!Objects.equals(currentVersion.getId(), failedVersion.getId())
            && currentVersion.getStatus() == DocumentVersionStatus.INDEXED
            && document.getStatus() == DocumentStatus.INDEXED) {
            return;
        }

        boolean noSearchableVersion = Objects.equals(currentVersion.getId(), failedVersion.getId())
            || currentVersion.getStatus() == DocumentVersionStatus.FAILED;
        if (noSearchableVersion
            && (document.getStatus() == DocumentStatus.UPLOADED
                || document.getStatus() == DocumentStatus.INDEXING
                || document.getStatus() == DocumentStatus.FAILED)) {
            document.markFailed();
            return;
        }
        throw new DocGridException(ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT);
    }

    /**
     * 실패한 처리 단계와 선택적인 Attempt 정보를 파싱·임베딩 실패 이벤트로 저장한다.
     */
    private void saveStageFailureEvent(
        EmbeddingJob embeddingJob,
        Optional<EmbeddingJobAttempt> attempt,
        IndexingEventType eventType,
        String fromStatus,
        String toStatus,
        String failureCode,
        LocalDateTime failedAt
    ) {
        String message = eventType == IndexingEventType.PARSE_FAILED
            ? PARSE_FAILURE_MESSAGE
            : EMBEDDING_FAILURE_MESSAGE;
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(eventType)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .message(message)
            .metadataJson(stageFailureMetadata(attempt, failureCode))
            .occurredAt(failedAt)
            .build());
    }

    /**
     * 단계 실패 이벤트에 기록할 Attempt와 실패 유형 Metadata JSON을 생성한다.
     */
    private String stageFailureMetadata(
        Optional<EmbeddingJobAttempt> attempt,
        String failureCode
    ) {
        return attempt
            .map(currentAttempt ->
                """
                    {"attemptId":%d,"attemptNo":%d,"failureType":"%s"}
                    """.formatted(
                        currentAttempt.getId(),
                        currentAttempt.getAttemptNo(),
                        failureCode
                    ).strip()
            )
            .orElse("""
                {"failureType":"%s"}
                """.formatted(failureCode).strip());
    }

    /**
     * 재시도 횟수와 다음 실행 시각을 이벤트 Metadata JSON으로 생성한다.
     */
    private String retryMetadata(EmbeddingJob embeddingJob, LocalDateTime nextRetryAt) {
        return """
            {"retryCount":%d,"nextRetryAt":"%s"}
            """.formatted(embeddingJob.getRetryCount(), nextRetryAt).strip();
    }

    /**
     * 최종 실패 시점의 사용한 Retry 횟수와 허용 한도를 Metadata JSON으로 생성한다.
     */
    private String terminalFailureMetadata(EmbeddingJob embeddingJob) {
        return """
            {"retryCount":%d,"maxRetryCount":%d}
            """.formatted(
                embeddingJob.getRetryCount(),
                embeddingJob.getMaxRetryCount()
            ).strip();
    }
}
