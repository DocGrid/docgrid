package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingCompletionResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.EmbeddingJobAttemptMetricEvent;

/**
 * 문서 인덱싱 최초 완료 Transaction의 잠금 순서, 검증, 상태 전이와 이전 검색 Set 비활성화를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentIndexingCompletionService 테스트")
class DocumentIndexingCompletionServiceTest {

    private static final Long JOB_ID = 41L;
    private static final Long ATTEMPT_ID = 103L;
    private static final Long DOCUMENT_ID = 10L;
    private static final Long VERSION_ID = 22L;
    private static final Long MODEL_ID = 1L;
    private static final Long WORKER_ID = WorkerNodeFixture.WORKER_ID;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 7, 31, 16, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private DocumentIndexingCompletionService service;
    private Document document;
    private DocumentVersion documentVersion;
    private EmbeddingModel embeddingModel;
    private WorkerNode workerNode;
    private EmbeddingJob embeddingJob;
    private EmbeddingJobAttempt attempt;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-31T07:00:00Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new DocumentIndexingCompletionService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            documentVersionRepository,
            documentRepository,
            documentChunkRepository,
            embeddingRepository,
            indexingEventRepository,
            ownershipValidator,
            clock,
            applicationEventPublisher
        );
        prepareExecution();
    }

    @Test
    @DisplayName("최초 Version의 전체 Embedding Set을 검증하고 모든 완료 상태를 같은 시각으로 전환한다")
    void complete_transitionsFirstVersionAtomically() {
        givenLockedExecution();
        givenValidCompletionState();

        DocumentIndexingCompletionResponse response = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            request()
        );

        assertThat(response.jobId()).isEqualTo(JOB_ID);
        assertThat(response.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(response.documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(response.embeddingModelId()).isEqualTo(MODEL_ID);
        assertThat(response.jobStatus()).isEqualTo(EmbeddingJobStatus.INDEXED);
        assertThat(response.attemptStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(response.versionStatus()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(response.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(response.durationMs()).isEqualTo(8_000L);

        assertThat(document.getCurrentVersion()).isSameAs(documentVersion);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(documentVersion.getIndexedAt()).isEqualTo(COMPLETED_AT);
        assertThat(embeddingJob.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(attempt.getEndedAt()).isEqualTo(COMPLETED_AT);
        then(embeddingRepository).should(never())
            .markActiveAsStaleByDocumentVersionId(VERSION_ID);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(IndexingEventType.INDEXED);
        assertThat(eventCaptor.getValue().getFromStatus()).isEqualTo("EMBEDDING");
        assertThat(eventCaptor.getValue().getToStatus()).isEqualTo("INDEXED");
        assertThat(eventCaptor.getValue().getOccurredAt()).isEqualTo(COMPLETED_AT);
        then(applicationEventPublisher).should()
            .publishEvent(EmbeddingJobAttemptMetricEvent.success());
    }

    @Test
    @DisplayName("새 Version 완료 시 이전 현재 Version의 ACTIVE Embedding을 STALE로 전환한다")
    void complete_stalesPreviousCurrentVersion() {
        DocumentVersion previousVersion = version(21L, 1, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(previousVersion);
        documentVersion = version(VERSION_ID, 2, DocumentVersionStatus.EMBEDDING);
        prepareJobAndAttempt();
        givenLockedExecution();
        givenValidCompletionState();
        given(embeddingRepository.markActiveAsStaleByDocumentVersionId(21L)).willReturn(2);

        service.complete(JOB_ID, ATTEMPT_ID, request());

        then(embeddingRepository).should().markActiveAsStaleByDocumentVersionId(21L);
        assertThat(previousVersion.getStatus()).isEqualTo(DocumentVersionStatus.INDEXED);
        assertThat(document.getCurrentVersion()).isSameAs(documentVersion);
    }

    @Test
    @DisplayName("최신 Version이 아닌 완료 요청은 상태 변경 전에 거부한다")
    void complete_rejectsStaleVersion() {
        givenLockedExecution();
        DocumentVersion newerVersion = version(23L, 2, DocumentVersionStatus.UPLOADED);
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(newerVersion));

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, request()))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_INDEXING_STALE_COMPLETION));

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        then(embeddingRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Embedding Set 개수가 Chunk 수와 다르면 완료 상태를 만들지 않는다")
    void complete_rejectsIncompleteEmbeddingSet() {
        givenLockedExecution();
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(documentVersion));
        given(embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            VERSION_ID,
            java.util.Set.of(EmbeddingJobStatus.PENDING, EmbeddingJobStatus.PROCESSING)
        )).willReturn(1L);
        given(documentChunkRepository.countByDocumentVersionId(VERSION_ID)).willReturn(2L);
        given(embeddingRepository.countByDocumentVersionId(VERSION_ID)).willReturn(2L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(2L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            VERSION_ID,
            MODEL_ID,
            EmbeddingStatus.ACTIVE
        )).willReturn(1L);

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, request()))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT));

        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PROCESSING이 아닌 Job은 소유권 검증 전에 완료를 거부한다")
    void complete_rejectsUnexpectedJobStatus() {
        EmbeddingJob pendingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .embeddingModel(embeddingModel)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(pendingJob, "id", JOB_ID);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID))
            .willReturn(Optional.of(pendingJob));

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, request()))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED));

        then(ownershipValidator).shouldHaveNoInteractions();
        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("완료된 같은 실행은 Lease와 현재 Version이 바뀌어도 최초 완료 결과를 재생한다")
    void complete_replaysStoredCompletionAfterLeaseExpiryAndNewerVersion() {
        prepareCompletedExecution();
        ReflectionTestUtils.setField(
            embeddingJob,
            "lockExpiresAt",
            COMPLETED_AT.minusSeconds(1)
        );
        EmbeddingModel inactiveModel =
            EmbeddingModelFixture.createModel("inactive-completed-model", false, false);
        ReflectionTestUtils.setField(inactiveModel, "id", MODEL_ID);
        ReflectionTestUtils.setField(embeddingJob, "embeddingModel", inactiveModel);
        DocumentVersion newerVersion = version(23L, 2, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(newerVersion);
        givenCompletedExecution();
        given(indexingEventRepository.countByEmbeddingJobIdAndEventType(
            JOB_ID,
            IndexingEventType.INDEXED
        )).willReturn(1L);

        DocumentIndexingCompletionResponse response = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            request()
        );

        assertThat(response.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(response.durationMs()).isEqualTo(8_000L);
        assertThat(response.documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(document.getCurrentVersion()).isSameAs(newerVersion);
        then(ownershipValidator).shouldHaveNoInteractions();
        then(documentChunkRepository).shouldHaveNoInteractions();
        then(embeddingRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("완료 재생의 Worker나 Claim Token이 다르면 소유권 오류로 거부한다")
    void complete_replayRejectsDifferentIdentity() {
        prepareCompletedExecution();
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID))
            .willReturn(Optional.of(embeddingJob));
        CompleteDocumentIndexingRequest differentToken = new CompleteDocumentIndexingRequest(
            WORKER_ID,
            "8d242ac5-0916-4e1c-a781-1f7b932f989b"
        );

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, differentToken))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INVALID));

        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
        then(documentVersionRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("완료 재생의 Attempt가 SUCCESS가 아니면 실행 Context 오류로 거부한다")
    void complete_replayRejectsIncompleteAttempt() {
        prepareCompletedExecution();
        ReflectionTestUtils.setField(attempt, "status", AttemptStatus.FAILED);
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, request()))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        then(documentVersionRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("완료 재생의 저장 시각이나 INDEXED 이벤트 수가 모순이면 결과를 반환하지 않는다")
    void complete_replayRejectsInconsistentStoredState() {
        prepareCompletedExecution();
        ReflectionTestUtils.setField(attempt, "durationMs", null);
        givenCompletedExecution();

        assertThatThrownBy(() -> service.complete(JOB_ID, ATTEMPT_ID, request()))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_INDEXING_COMPLETION_INCONSISTENT));

        then(embeddingRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void prepareExecution() {
        document = Document.builder()
            .title("완료 대상 문서")
            .status(DocumentStatus.INDEXING)
            .build();
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        documentVersion = version(VERSION_ID, 1, DocumentVersionStatus.EMBEDDING);
        document.updateCurrentVersion(documentVersion);
        embeddingModel = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(embeddingModel, "id", MODEL_ID);
        workerNode = WorkerNodeFixture.createActiveWorker(COMPLETED_AT.minusSeconds(1));
        prepareJobAndAttempt();
    }

    private void prepareJobAndAttempt() {
        embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .embeddingModel(embeddingModel)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        embeddingJob.claim(
            workerNode,
            CLAIM_TOKEN,
            COMPLETED_AT.minusMinutes(1),
            COMPLETED_AT.plusMinutes(5)
        );
        attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(workerNode)
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .status(AttemptStatus.STARTED)
            .startedAt(COMPLETED_AT.minusSeconds(8))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
    }

    private void prepareCompletedExecution() {
        documentVersion.markIndexed(COMPLETED_AT);
        document.activateIndexedVersion(documentVersion);
        attempt.markSuccess(COMPLETED_AT, 8_000L);
        embeddingJob.markIndexed(COMPLETED_AT);
    }

    private DocumentVersion version(Long id, int versionNo, DocumentVersionStatus status) {
        DocumentVersion version = DocumentVersion.builder()
            .document(document)
            .versionNo(versionNo)
            .status(status)
            .build();
        ReflectionTestUtils.setField(version, "id", id);
        return version;
    }

    private void givenLockedExecution() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID))
            .willReturn(Optional.of(document));
    }

    private void givenValidCompletionState() {
        given(documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID))
            .willReturn(Optional.of(documentVersion));
        given(embeddingJobRepository.countByDocumentVersionIdAndStatusIn(
            VERSION_ID,
            java.util.Set.of(EmbeddingJobStatus.PENDING, EmbeddingJobStatus.PROCESSING)
        )).willReturn(1L);
        given(documentChunkRepository.countByDocumentVersionId(VERSION_ID)).willReturn(2L);
        given(embeddingRepository.countByDocumentVersionId(VERSION_ID)).willReturn(2L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(2L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            VERSION_ID,
            MODEL_ID,
            EmbeddingStatus.ACTIVE
        )).willReturn(2L);
        given(embeddingRepository.countInvalidCompletionRows(
            DOCUMENT_ID,
            VERSION_ID,
            MODEL_ID,
            EmbeddingModelFixture.DIMENSION
        )).willReturn(0L);
        given(indexingEventRepository.countByEmbeddingJobIdAndEventType(
            JOB_ID,
            IndexingEventType.INDEXED
        )).willReturn(0L);
    }

    private void givenCompletedExecution() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID))
            .willReturn(Optional.of(documentVersion));
        given(documentRepository.findByIdForUpdate(DOCUMENT_ID))
            .willReturn(Optional.of(document));
    }

    private CompleteDocumentIndexingRequest request() {
        return new CompleteDocumentIndexingRequest(WORKER_ID, CLAIM_TOKEN);
    }
}
