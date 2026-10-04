package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingDraft;
import com.opensource.docgrid.domain.embedding.service.EmbeddingVectorSupport;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.CompletionResult;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.PreparationResult;
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

/**
 * Chunk Embedding 준비·완료 Transaction의 상태 전이, 재개, 완료 재생과 원자 저장 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentEmbeddingTransactionService 테스트")
class DocumentEmbeddingTransactionServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long VERSION_ID = 5L;
    private static final Long MODEL_ID = 7L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 12, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;

    private DocumentEmbeddingTransactionService service;
    private EmbeddingJob embeddingJob;
    private DocumentVersion documentVersion;
    private EmbeddingModel embeddingModel;
    private EmbeddingJobAttempt attempt;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-29T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new DocumentEmbeddingTransactionService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            documentVersionRepository,
            documentChunkRepository,
            embeddingRepository,
            indexingEventRepository,
            ownershipValidator,
            clock
        );
        prepareEntities(DocumentVersionStatus.CHUNKED);
    }

    @Test
    @DisplayName("CHUNKED Version을 EMBEDDING으로 전환하고 고정 Model의 Chunk Snapshot을 만든다")
    void prepare_marksEmbeddingAndReturnsSnapshot() {
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.work().documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.work().embeddingModelId()).isEqualTo(MODEL_ID);
        assertThat(result.work().dimension()).isEqualTo(EmbeddingModelFixture.DIMENSION);
        assertThat(result.work().chunks())
            .extracting(chunk -> chunk.chunkIndex() + ":" + chunk.chunkText())
            .containsExactly("0:첫 번째", "1:두 번째");
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        then(ownershipValidator).should().validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, NOW);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(IndexingEventType.EMBEDDING_STARTED);
    }

    @Test
    @DisplayName("Section 경로 Metadata가 있는 Chunk만 Embedding 입력에 경로를 붙이고 원문은 유지한다")
    void prepare_buildsEmbeddingTextFromSectionPath() {
        givenValidContext(List.of(
            chunk(0, "경로 있는 본문", "{\"headingPath\":[\"3. 환불 정책\",\"3.2 개봉 후 환불\"],\"headingLevel\":2}"),
            chunk(1, "경로 없는 본문")
        ));

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.work().chunks()).extracting(ChunkSnapshot::chunkText)
            .containsExactly("경로 있는 본문", "경로 없는 본문");
        assertThat(result.work().chunks()).extracting(ChunkSnapshot::embeddingText)
            .containsExactly("3. 환불 정책 > 3.2 개봉 후 환불\n경로 있는 본문", "경로 없는 본문");
    }

    @Test
    @DisplayName("저장 결과가 없는 EMBEDDING Version은 시작 이벤트 없이 작업을 재개한다")
    void prepare_resumesEmbeddingWithoutDuplicateEvent() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "본문")));

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.work().chunks()).hasSize(1);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("모든 Chunk의 Embedding이 저장됐으면 기존 완료 결과를 재생한다")
    void prepare_replaysCompletedEmbeddings() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(2L);

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isTrue();
        assertThat(result.work()).isNull();
        assertThat(result.replayResult().created()).isFalse();
        assertThat(result.replayResult().embeddingCount()).isEqualTo(2);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Reconciler가 확인한 일부 Embedding Set은 누락 Vector 생성을 재개한다")
    void prepare_resumesPartialEmbeddings() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        givenValidContext(List.of(chunk(0, "첫 번째"), chunk(1, "두 번째")));
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(1L);

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.isReplay()).isFalse();
        assertThat(result.work().chunks()).hasSize(2);
    }

    @Test
    @DisplayName("현재 Claim과 일치하지 않는 Attempt는 Version 조회 전에 거부한다")
    void prepare_rejectsInvalidAttempt() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        then(documentVersionRepository).shouldHaveNoInteractions();
        then(documentChunkRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Chunk Index가 연속되지 않으면 외부 호출 Snapshot을 만들지 않는다")
    void prepare_rejectsNonSequentialChunks() {
        givenValidContext(List.of(chunk(1, "본문")));

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT));

        then(embeddingRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("CHUNKED나 EMBEDDING이 아닌 Version은 Embedding 준비를 거부한다")
    void prepare_rejectsUnexpectedVersionStatus() {
        prepareEntities(DocumentVersionStatus.INDEXED);
        givenValidContext(List.of(chunk(0, "본문")));

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED));
    }

    @Test
    @DisplayName("검증된 Draft 전체를 ACTIVE Embedding Set으로 저장하고 EMBEDDING 상태를 유지한다")
    @SuppressWarnings("unchecked")
    void complete_savesEmbeddingSet() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "첫 번째"), chunk(1, "두 번째"));
        givenValidContext(chunks);
        EmbeddingWork work = work(chunks);
        List<DocumentEmbeddingDraft> drafts = List.of(
            draft(chunks.get(0), 0.1f),
            draft(chunks.get(1), 0.2f)
        );

        CompletionResult result = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work,
            drafts
        );

        assertThat(result.created()).isTrue();
        assertThat(result.embeddingCount()).isEqualTo(2);
        assertThat(result.documentVersionStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);

        ArgumentCaptor<List<Embedding>> embeddingsCaptor = ArgumentCaptor.forClass(List.class);
        then(embeddingRepository).should().saveAllAndFlush(embeddingsCaptor.capture());
        Embedding first = embeddingsCaptor.getValue().get(0);
        assertThat(first.getChunk()).isSameAs(chunks.get(0));
        assertThat(first.getDocumentVersion()).isSameAs(documentVersion);
        assertThat(first.getEmbeddingModel()).isSameAs(embeddingModel);
        assertThat(first.getDimension()).isEqualTo(EmbeddingModelFixture.DIMENSION);
        assertThat(first.getVector()).hasSize(EmbeddingModelFixture.DIMENSION);
        assertThat(first.getStatus()).isEqualTo(EmbeddingStatus.ACTIVE);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Embedding 입력에 경로가 붙어도 원문 Chunk 일치 검증은 통과해 Embedding Set을 저장한다")
    void complete_savesEmbeddingSetWhenEmbeddingTextDiffersFromChunkText() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "본문"));
        givenValidContext(chunks);
        EmbeddingWork work = new EmbeddingWork(
            VERSION_ID,
            MODEL_ID,
            EmbeddingModelFixture.MODEL_NAME,
            EmbeddingModelFixture.DIMENSION,
            List.of(new ChunkSnapshot(
                chunks.get(0).getId(), 0, "본문", "1장 > 1절\n본문", 1, chunks.get(0).getContentHash()
            ))
        );

        CompletionResult result = service.complete(
            JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN, work, List.of(draft(chunks.get(0), 0.1f))
        );

        assertThat(result.created()).isTrue();
        assertThat(result.embeddingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("부분 Embedding Set 복구는 기존 Vector를 보존하고 누락 Chunk만 저장한다")
    @SuppressWarnings("unchecked")
    void complete_preservesExistingEmbeddingAndSavesMissingChunk() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "첫 번째"), chunk(1, "두 번째"));
        givenValidContext(chunks);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(1L);
        given(embeddingRepository.findChunkIdsByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(List.of(chunks.get(0).getId()));

        CompletionResult result = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work(chunks),
            List.of(draft(chunks.get(0), 0.1f), draft(chunks.get(1), 0.2f))
        );

        assertThat(result.embeddingCount()).isEqualTo(2);
        ArgumentCaptor<List<Embedding>> embeddingsCaptor = ArgumentCaptor.forClass(List.class);
        then(embeddingRepository).should().saveAllAndFlush(embeddingsCaptor.capture());
        assertThat(embeddingsCaptor.getValue())
            .singleElement()
            .extracting(Embedding::getChunk)
            .isSameAs(chunks.get(1));
    }

    @Test
    @DisplayName("완료 단계에서 선행 요청의 전체 저장을 발견하면 Insert 없이 재생한다")
    void complete_replaysConcurrentWinner() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "첫 번째"), chunk(1, "두 번째"));
        givenValidContext(chunks);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(VERSION_ID, MODEL_ID))
            .willReturn(2L);

        CompletionResult result = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work(chunks),
            List.of(draft(chunks.get(0), 0.1f), draft(chunks.get(1), 0.2f))
        );

        assertThat(result.created()).isFalse();
        assertThat(result.embeddingCount()).isEqualTo(2);
        then(embeddingRepository).should(never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("외부 호출 중 Chunk Text가 바뀌면 Draft 저장을 거부한다")
    void complete_rejectsChangedChunk() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> originalChunks = List.of(chunk(0, "원본"));
        EmbeddingWork work = work(originalChunks);
        List<DocumentChunk> changedChunks = List.of(chunk(0, "변경"));
        givenValidContext(changedChunks);

        assertThatThrownBy(() -> service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work,
            List.of(draft(originalChunks.get(0), 0.1f))
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT));

        then(embeddingRepository).should(never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("Vector 내용과 Hash가 일치하지 않으면 Draft 저장을 거부한다")
    void complete_rejectsTamperedVectorHash() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "본문"));
        givenValidContext(chunks);
        float[] vector = vector(0.1f);
        DocumentEmbeddingDraft tampered = new DocumentEmbeddingDraft(
            chunks.get(0).getId(),
            0,
            CONTENT_HASH,
            vector,
            "0".repeat(64)
        );

        assertThatThrownBy(() -> service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            work(chunks),
            List.of(tampered)
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_VECTOR_INVALID));

        then(embeddingRepository).should(never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("준비 Snapshot의 Model이 현재 Job 고정 Model과 다르면 Chunk를 다시 읽지 않는다")
    void complete_rejectsChangedModel() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "본문"));
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID)).willReturn(Optional.of(documentVersion));
        EmbeddingWork changedModelWork = new EmbeddingWork(
            VERSION_ID,
            99L,
            EmbeddingModelFixture.MODEL_NAME,
            EmbeddingModelFixture.DIMENSION,
            work(chunks).chunks()
        );

        assertThatThrownBy(() -> service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            changedModelWork,
            List.of(draft(chunks.get(0), 0.1f))
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));

        then(documentChunkRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("준비 Snapshot의 Model Name이 현재 Job 고정 Model과 다르면 Chunk를 다시 읽지 않는다")
    void complete_rejectsChangedModelName() {
        prepareEntities(DocumentVersionStatus.EMBEDDING);
        List<DocumentChunk> chunks = List.of(chunk(0, "본문"));
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID)).willReturn(Optional.of(documentVersion));
        EmbeddingWork changedModelWork = new EmbeddingWork(
            VERSION_ID,
            MODEL_ID,
            "different-model",
            EmbeddingModelFixture.DIMENSION,
            work(chunks).chunks()
        );

        assertThatThrownBy(() -> service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            changedModelWork,
            List.of(draft(chunks.get(0), 0.1f))
        ))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));

        then(documentChunkRepository).shouldHaveNoInteractions();
    }

    private void givenValidContext(List<DocumentChunk> chunks) {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID)).willReturn(Optional.of(documentVersion));
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(VERSION_ID))
            .willReturn(chunks);
    }

    private void prepareEntities(DocumentVersionStatus versionStatus) {
        WorkerNode worker = WorkerNode.builder()
            .workerName("worker")
            .instanceId("instance")
            .status(WorkerStatus.ACTIVE)
            .startedAt(NOW.minusHours(1))
            .build();
        ReflectionTestUtils.setField(worker, "id", WORKER_ID);

        Document document = Document.builder()
            .title("문서")
            .documentType(DocumentType.TXT)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(DocumentStatus.INDEXING)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", 3L);

        documentVersion = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(versionStatus)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);

        embeddingModel = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(embeddingModel, "id", MODEL_ID);

        embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .embeddingModel(embeddingModel)
            .status(EmbeddingJobStatus.PROCESSING)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);

        attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(worker)
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .status(AttemptStatus.STARTED)
            .startedAt(NOW.minusMinutes(1))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
    }

    private DocumentChunk chunk(int index, String text) {
        return chunk(index, text, null);
    }

    private DocumentChunk chunk(int index, String text, String metadataJson) {
        DocumentChunk chunk = DocumentChunk.builder()
            .documentVersion(documentVersion)
            .chunkIndex(index)
            .chunkText(text)
            .tokenCount(1)
            .charStart(index * 10)
            .charEnd(index * 10 + text.length())
            .contentHash(CONTENT_HASH)
            .metadataJson(metadataJson)
            .build();
        ReflectionTestUtils.setField(chunk, "id", 20L + index);
        return chunk;
    }

    private EmbeddingWork work(List<DocumentChunk> chunks) {
        return new EmbeddingWork(
            VERSION_ID,
            MODEL_ID,
            EmbeddingModelFixture.MODEL_NAME,
            EmbeddingModelFixture.DIMENSION,
            chunks.stream()
                .map(chunk -> new ChunkSnapshot(
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getChunkText(),
                    chunk.getTokenCount(),
                    chunk.getContentHash()
                ))
                .toList()
        );
    }

    private DocumentEmbeddingDraft draft(DocumentChunk chunk, float firstValue) {
        float[] vector = vector(firstValue);
        return new DocumentEmbeddingDraft(
            chunk.getId(),
            chunk.getChunkIndex(),
            chunk.getContentHash(),
            vector,
            EmbeddingVectorSupport.calculateHash(vector)
        );
    }

    private float[] vector(float firstValue) {
        float[] vector = new float[EmbeddingModelFixture.DIMENSION];
        vector[0] = firstValue;
        return vector;
    }
}
