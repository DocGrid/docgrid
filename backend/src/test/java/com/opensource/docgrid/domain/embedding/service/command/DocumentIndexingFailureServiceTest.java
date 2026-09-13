package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobAttemptConverter;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.EmbeddingJobAttemptMetricEvent;

/**
 * 인덱싱 실패 Service의 Retry·최종 종료·멱등 재생 분기와 원자 상태 변경을 검증한다.
 *
 * <p>외부 I/O 없이 Job → Version → Document 잠금 조회 뒤 Attempt, Queue, 검색 가용성과 이벤트가
 * 설계된 정책대로 함께 바뀌는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentIndexingFailureService 테스트")
class DocumentIndexingFailureServiceTest {

    private static final Long JOB_ID = 41L;
    private static final Long ATTEMPT_ID = 103L;
    private static final Long DOCUMENT_ID = 10L;
    private static final Long VERSION_ID = 22L;
    private static final Long WORKER_ID = 7L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 3, 10, 30);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private DocumentIndexingFailureService service;
    private IndexingWorkerProperties workerProperties;
    private Document document;
    private DocumentVersion documentVersion;
    private WorkerNode workerNode;
    private EmbeddingJob embeddingJob;
    private EmbeddingJobAttempt attempt;

    @BeforeEach
    void setUp() {
        workerProperties = new IndexingWorkerProperties();
        workerProperties.setRetryJitterRatio(0.0);
        Clock clock = Clock.fixed(
            Instant.parse("2026-08-03T01:30:00Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new DocumentIndexingFailureService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            documentVersionRepository,
            documentRepository,
            embeddingRepository,
            indexingEventRepository,
            ownershipValidator,
            new EmbeddingJobAttemptConverter(),
            workerProperties,
            clock,
            applicationEventPublisher
        );
        prepareExecution(DocumentVersionStatus.PARSING, DocumentStatus.INDEXING);
    }

    @Test
    @DisplayName("Provider Retry-After가 기본 Backoff보다 길면 다음 실행 최소 지연으로 적용한다")
    void fail_appliesProviderMinimumRetryDelay() {
        prepareExecution(DocumentVersionStatus.EMBEDDING, DocumentStatus.INDEXING);
        givenLockedExecution();

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED, "Provider overloaded"),
            Duration.ofSeconds(15)
        );

        assertThat(embeddingJob.getRetryCount()).isEqualTo(1);
        assertThat(embeddingJob.getNextRetryAt()).isEqualTo(FAILED_AT.plusSeconds(15));
    }

    @Test
    @DisplayName("일시적 파싱 실패는 Version을 보존하고 10초 뒤 Retry를 예약한다")
    void fail_schedulesInitialRetryAndRecordsEvents() {
        givenLockedExecution();

        DocumentIndexingFailureResponse response = service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.STORAGE_UNAVAILABLE, "Storage timeout")
        );

        assertThat(response.attemptStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(response.failureType()).isEqualTo(IndexingFailureType.STORAGE_UNAVAILABLE);
        assertThat(response.failedAt()).isEqualTo(FAILED_AT);
        assertThat(response.durationMs()).isEqualTo(8_000L);
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(embeddingJob.getRetryCount()).isEqualTo(1);
        assertThat(embeddingJob.getNextRetryAt()).isEqualTo(FAILED_AT.plusSeconds(10));
        assertThat(embeddingJob.getClaimToken()).isNull();
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.PARSING);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXING);
        then(embeddingRepository).shouldHaveNoInteractions();

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should(times(2)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .extracting(IndexingEvent::getEventType)
            .containsExactly(IndexingEventType.PARSE_FAILED, IndexingEventType.RETRY);
        assertThat(eventCaptor.getAllValues().get(0).getMetadataJson())
            .isEqualTo("{\"attemptId\":103,\"attemptNo\":1,\"failureType\":\"STORAGE_UNAVAILABLE\"}");
        assertThat(eventCaptor.getAllValues().get(1).getMetadataJson())
            .isEqualTo("{\"retryCount\":1,\"nextRetryAt\":\"2026-08-03T10:30:10\"}");
        then(applicationEventPublisher).should().publishEvent(
            EmbeddingJobAttemptMetricEvent.failure(
                EmbeddingJobStatus.PENDING,
                IndexingFailureType.STORAGE_UNAVAILABLE
            )
        );
    }

    @Test
    @DisplayName("누적 Retry 횟수가 커도 Backoff는 설정한 최대 지연을 넘지 않는다")
    void fail_capsRetryBackoffAtMaximumDelay() {
        workerProperties.setRetryMaxDelay(Duration.ofSeconds(40));
        ReflectionTestUtils.setField(embeddingJob, "retryCount", 4);
        ReflectionTestUtils.setField(embeddingJob, "maxRetryCount", 6);
        givenLockedExecution();

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.WORKER_INTERNAL_ERROR, "temporary worker failure")
        );

        assertThat(embeddingJob.getRetryCount()).isEqualTo(5);
        assertThat(embeddingJob.getNextRetryAt()).isEqualTo(FAILED_AT.plusSeconds(40));
    }

    @ParameterizedTest
    @CsvSource({"1, 20", "2, 40"})
    @DisplayName("현재 Retry 횟수에 따라 20초와 40초 지수 Backoff를 적용한다")
    void fail_doublesRetryDelay(int retryCount, long expectedDelaySeconds) {
        ReflectionTestUtils.setField(embeddingJob, "retryCount", retryCount);
        givenLockedExecution();

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE, "Provider timeout")
        );

        assertThat(embeddingJob.getNextRetryAt())
            .isEqualTo(FAILED_AT.plusSeconds(expectedDelaySeconds));
    }

    @Test
    @DisplayName("영구 임베딩 실패는 최초 Version과 Document 및 Job을 최종 실패로 종료한다")
    void fail_terminatesFirstVersionForPermanentFailure() {
        prepareExecution(DocumentVersionStatus.EMBEDDING, DocumentStatus.INDEXING);
        givenLockedExecution();
        given(embeddingRepository.markActiveAsStaleByDocumentVersionId(VERSION_ID)).willReturn(2);

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.EMBEDDING_RESULT_INVALID, "Vector dimension mismatch")
        );

        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.FAILED);
        assertThat(embeddingJob.getFailedAt()).isEqualTo(FAILED_AT);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.FAILED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        then(embeddingRepository).should().markActiveAsStaleByDocumentVersionId(VERSION_ID);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should(times(2)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .extracting(IndexingEvent::getEventType)
            .containsExactly(IndexingEventType.EMBEDDING_FAILED, IndexingEventType.FAILED);
        assertThat(eventCaptor.getAllValues().get(1).getMetadataJson())
            .isEqualTo("{\"retryCount\":0,\"maxRetryCount\":3}");
        then(applicationEventPublisher).should().publishEvent(
            EmbeddingJobAttemptMetricEvent.failure(
                EmbeddingJobStatus.FAILED,
                IndexingFailureType.EMBEDDING_RESULT_INVALID
            )
        );
    }

    @Test
    @DisplayName("재시도 가능 실패도 Retry 횟수를 소진하면 최종 실패로 종료한다")
    void fail_terminatesRetryableFailureWhenRetriesAreExhausted() {
        ReflectionTestUtils.setField(embeddingJob, "retryCount", 3);
        givenLockedExecution();

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.WORKER_INTERNAL_ERROR, "temporary worker failure")
        );

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.FAILED);
        assertThat(embeddingJob.getRetryCount()).isEqualTo(3);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.FAILED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    @DisplayName("새 Version 최종 실패 시 이전 INDEXED Version과 Document 검색 상태를 보존한다")
    void fail_preservesPreviousIndexedVersion() {
        prepareExecution(DocumentVersionStatus.EMBEDDING, DocumentStatus.INDEXED);
        DocumentVersion previousVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(DocumentVersionStatus.INDEXED)
            .build();
        ReflectionTestUtils.setField(previousVersion, "id", 21L);
        document.updateCurrentVersion(previousVersion);
        givenLockedExecution();

        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.DOCUMENT_CONTENT_INVALID, "Invalid document content")
        );

        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.FAILED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(document.getCurrentVersion()).isSameAs(previousVersion);
        then(embeddingRepository).should().markActiveAsStaleByDocumentVersionId(VERSION_ID);
    }

    @Test
    @DisplayName("같은 실패 요청은 Job과 Lease가 바뀌어도 저장된 Attempt 결과만 재생한다")
    void fail_replaysStoredFailureWithoutStateChanges() {
        givenLockedExecution();
        FailDocumentIndexingRequest request =
            request(IndexingFailureType.STORAGE_UNAVAILABLE, "Storage timeout");
        service.fail(JOB_ID, ATTEMPT_ID, request);
        LocalDateTime firstNextRetryAt = embeddingJob.getNextRetryAt();

        DocumentIndexingFailureResponse replayed = service.fail(JOB_ID, ATTEMPT_ID, request);

        assertThat(replayed.failedAt()).isEqualTo(FAILED_AT);
        assertThat(replayed.durationMs()).isEqualTo(8_000L);
        assertThat(embeddingJob.getRetryCount()).isEqualTo(1);
        assertThat(embeddingJob.getNextRetryAt()).isEqualTo(firstNextRetryAt);
        then(ownershipValidator).should(times(1))
            .validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, FAILED_AT);
        then(documentVersionRepository).should(times(1)).findByIdForUpdate(VERSION_ID);
        then(indexingEventRepository).should(times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("이미 실패한 Attempt의 다른 실패 내용은 충돌로 거부한다")
    void fail_rejectsDifferentFailureReplay() {
        givenLockedExecution();
        service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.STORAGE_UNAVAILABLE, "Storage timeout")
        );

        assertThatThrownBy(() -> service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.STORAGE_UNAVAILABLE, "different failure")
        )).isInstanceOfSatisfying(DocGridException.class,
            exception -> assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.EMBEDDING_JOB_FAILURE_CONFLICT));
    }

    @Test
    @DisplayName("STARTED Attempt의 Job이 PROCESSING이 아니면 소유권 검증 전에 거부한다")
    void fail_rejectsUnexpectedJobStatus() {
        ReflectionTestUtils.setField(embeddingJob, "status", EmbeddingJobStatus.PENDING);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.fail(
            JOB_ID,
            ATTEMPT_ID,
            request(IndexingFailureType.WORKER_INTERNAL_ERROR, "temporary failure")
        )).isInstanceOfSatisfying(DocGridException.class,
            exception -> assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.EMBEDDING_JOB_NOT_PROCESSING));

        then(ownershipValidator).shouldHaveNoInteractions();
        then(documentVersionRepository).shouldHaveNoInteractions();
    }

    private void prepareExecution(
        DocumentVersionStatus versionStatus,
        DocumentStatus documentStatus
    ) {
        document = Document.builder()
            .title("Failure service document")
            .status(documentStatus)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        documentVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(versionStatus)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);
        document.updateCurrentVersion(documentVersion);

        workerNode = WorkerNode.builder()
            .workerName("failure-worker")
            .instanceId("failure-worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(FAILED_AT.minusSeconds(1))
            .startedAt(FAILED_AT.minusMinutes(1))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);

        embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        embeddingJob.claim(
            workerNode,
            CLAIM_TOKEN,
            FAILED_AT.minusSeconds(10),
            FAILED_AT.plusMinutes(5)
        );
        attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(workerNode)
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .startedAt(FAILED_AT.minusSeconds(8))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
    }

    private void givenLockedExecution() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID)).willReturn(Optional.of(document));
    }

    private FailDocumentIndexingRequest request(
        IndexingFailureType failureType,
        String errorMessage
    ) {
        return new FailDocumentIndexingRequest(
            WORKER_ID,
            CLAIM_TOKEN,
            failureType,
            errorMessage
        );
    }
}
