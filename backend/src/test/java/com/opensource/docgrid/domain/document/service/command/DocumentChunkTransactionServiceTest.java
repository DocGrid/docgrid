package com.opensource.docgrid.domain.document.service.command;

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
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.PreparationResult;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobOwnershipValidator;
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
 * Chunk 준비·완료 Transaction의 상태, Attempt, 멱등 재생과 원자 저장 경계를 검증한다.
 *
 * <p>Mock Repository와 고정 Clock으로 Job·Version 잠금 뒤 외부 Snapshot 생성 및 Chunk Set,
 * CHUNKED 상태와 이벤트 저장이 같은 Command 호출에 포함되는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentChunkTransactionService 테스트")
class DocumentChunkTransactionServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long ATTEMPT_ID = 100L;
    private static final Long VERSION_ID = 5L;
    private static final Long WORKER_ID = 1L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 12, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobOwnershipValidator ownershipValidator;

    private DocumentChunkTransactionService service;
    private EmbeddingJob embeddingJob;
    private Document document;
    private DocumentVersion documentVersion;
    private EmbeddingJobAttempt attempt;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-29T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
        );
        service = new DocumentChunkTransactionService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            documentVersionRepository,
            documentChunkRepository,
            indexingEventRepository,
            ownershipValidator,
            clock
        );
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.UPLOADED);
    }

    @Test
    @DisplayName("UPLOADED Version을 PARSING으로 전환하고 시작 이벤트와 파일 Snapshot을 만든다")
    void prepare_marksParsingAndReturnsSnapshot() {
        givenValidContext();

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.replayResult()).isNull();
        assertThat(result.fileSnapshot().documentVersionId()).isEqualTo(VERSION_ID);
        assertThat(result.fileSnapshot().documentType()).isEqualTo(DocumentType.TXT);
        assertThat(result.fileSnapshot().storedFile().bucketName()).isEqualTo("bucket");
        assertThat(result.fileSnapshot().storedFile().objectKey()).isEqualTo("source.txt");
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.PARSING);
        then(ownershipValidator).should().validate(embeddingJob, WORKER_ID, CLAIM_TOKEN, NOW);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(IndexingEventType.PARSE_STARTED);
    }

    @Test
    @DisplayName("검색 가능한 Version이 없는 문서는 파싱 시작 시 INDEXING으로 전환된다")
    void prepare_marksDocumentIndexingWhenNotSearchable() {
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.UPLOADED, DocumentStatus.UPLOADED);
        givenValidContext();

        service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXING);
    }

    @Test
    @DisplayName("이미 INDEXED인 문서의 재인덱싱은 검색 가용성을 위해 INDEXED를 유지한다")
    void prepare_keepsIndexedDocumentSearchableWhileReindexing() {
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.UPLOADED, DocumentStatus.INDEXED);
        givenValidContext();

        service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        // 검색 경로가 d.status = 'INDEXED'로 필터하므로 여기서 상태가 바뀌면 기존 Version이 검색에서 이탈한다.
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.PARSING);
    }

    @Test
    @DisplayName("PARSING 재개는 상태와 시작 이벤트를 다시 만들지 않는다")
    void prepare_resumesParsingWithoutDuplicateEvent() {
        prepareEntities(DocumentType.MD, DocumentVersionStatus.PARSING);
        givenValidContext();

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.fileSnapshot().documentType()).isEqualTo(DocumentType.MD);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.PARSING);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PDF와 DOCX Content-Type을 파싱 준비 단계에서 허용한다")
    void prepare_acceptsPdfAndDocxContentTypes() {
        prepareEntities(DocumentType.PDF, DocumentVersionStatus.UPLOADED);
        givenValidContext();

        PreparationResult pdfResult = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(pdfResult.fileSnapshot().documentType()).isEqualTo(DocumentType.PDF);

        prepareEntities(DocumentType.DOCX, DocumentVersionStatus.UPLOADED);
        givenValidContext();

        PreparationResult docxResult = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(docxResult.fileSnapshot().documentType()).isEqualTo(DocumentType.DOCX);
    }

    @Test
    @DisplayName("브라우저가 octet-stream으로 올린 Markdown도 파싱 준비 단계에서 허용한다")
    void prepare_acceptsMarkdownUploadedAsOctetStream() {
        prepareEntities(DocumentType.MD, DocumentVersionStatus.UPLOADED);
        ReflectionTestUtils.setField(documentVersion, "contentType", "application/octet-stream");
        givenValidContext();

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.fileSnapshot().documentType()).isEqualTo(DocumentType.MD);
    }

    @Test
    @DisplayName("CHUNKED Version은 기존 Chunk 수를 반환하고 외부 Snapshot을 만들지 않는다")
    void prepare_replaysCompletedChunks() {
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.CHUNKED);
        givenValidContext();
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);
        given(documentChunkRepository.countByDocumentVersionId(VERSION_ID)).willReturn(3L);

        PreparationResult result = service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN);

        assertThat(result.fileSnapshot()).isNull();
        assertThat(result.replayResult().created()).isFalse();
        assertThat(result.replayResult().response().chunkCount()).isEqualTo(3);
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("현재 Claim과 일치하지 않는 Attempt는 Chunk 준비를 거부한다")
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
    @DisplayName("지원하지 않는 Document Type은 Storage 작업 전에 거부한다")
    void prepare_rejectsUnsupportedDocumentType() {
        prepareEntities(DocumentType.HTML, DocumentVersionStatus.UPLOADED);
        givenValidContext();

        assertThatThrownBy(() -> service.prepare(JOB_ID, ATTEMPT_ID, WORKER_ID, CLAIM_TOKEN))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));

        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PARSING Version에 Chunk 전체와 완료 이벤트를 저장하고 CHUNKED로 전환한다")
    @SuppressWarnings("unchecked")
    void complete_savesChunkSetAndMarksChunked() {
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.PARSING);
        givenValidContext();
        List<DocumentChunkDraft> drafts = List.of(draftWithSource());

        ChunkResult result = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            VERSION_ID,
            drafts
        );

        assertThat(result.created()).isTrue();
        assertThat(result.response().chunkCount()).isOne();
        assertThat(result.response().versionStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);
        assertThat(documentVersion.getStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);

        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        then(documentChunkRepository).should().saveAllAndFlush(chunksCaptor.capture());
        DocumentChunk saved = chunksCaptor.getValue().get(0);
        assertThat(saved.getDocumentVersion()).isSameAs(documentVersion);
        assertThat(saved.getChunkIndex()).isZero();
        assertThat(saved.getChunkText()).isEqualTo("본문");
        assertThat(saved.getPageNo()).isEqualTo(2);
        assertThat(saved.getSectionTitle()).isEqualTo("Section");
        assertThat(saved.getMetadataJson()).isEqualTo("{\"source\":\"pdf\"}");
        assertThat(saved.getContentHash()).hasSize(64);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(IndexingEventType.CHUNKED);
    }

    @Test
    @DisplayName("완료 단계에서 먼저 저장된 CHUNKED 결과를 발견하면 Insert 없이 재생한다")
    void complete_replaysConcurrentWinner() {
        prepareEntities(DocumentType.TXT, DocumentVersionStatus.CHUNKED);
        givenValidContext();
        given(documentChunkRepository.existsByDocumentVersionId(VERSION_ID)).willReturn(true);
        given(documentChunkRepository.countByDocumentVersionId(VERSION_ID)).willReturn(2L);

        ChunkResult result = service.complete(
            JOB_ID,
            ATTEMPT_ID,
            WORKER_ID,
            CLAIM_TOKEN,
            VERSION_ID,
            List.of(draft(0, "본문", 0, 2))
        );

        assertThat(result.created()).isFalse();
        assertThat(result.response().chunkCount()).isEqualTo(2);
        then(documentChunkRepository).should(never()).saveAllAndFlush(any());
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    private void givenValidContext() {
        given(embeddingJobRepository.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));
        given(documentVersionRepository.findByIdForUpdate(VERSION_ID)).willReturn(Optional.of(documentVersion));
    }

    private void prepareEntities(DocumentType documentType, DocumentVersionStatus versionStatus) {
        prepareEntities(documentType, versionStatus, DocumentStatus.INDEXING);
    }

    private void prepareEntities(
        DocumentType documentType,
        DocumentVersionStatus versionStatus,
        DocumentStatus documentStatus
    ) {
        WorkerNode worker = WorkerNode.builder()
            .workerName("worker")
            .instanceId("instance")
            .status(WorkerStatus.ACTIVE)
            .startedAt(NOW.minusHours(1))
            .build();
        ReflectionTestUtils.setField(worker, "id", WORKER_ID);

        document = Document.builder()
            .title("문서")
            .documentType(documentType)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(documentStatus)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", 3L);

        FileObject fileObject = FileObject.builder()
            .bucketName("bucket")
            .objectKey("source." + extension(documentType))
            .originalFilename("source." + extension(documentType))
            .contentType(contentType(documentType))
            .fileSize(6L)
            .fileHash("hash")
            .storageProvider(StorageProvider.MINIO)
            .uploadedAt(NOW.minusMinutes(10))
            .build();
        ReflectionTestUtils.setField(fileObject, "id", 4L);

        documentVersion = DocumentVersion.builder()
            .document(document)
            .fileObject(fileObject)
            .versionNo(1)
            .contentType(fileObject.getContentType())
            .status(versionStatus)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);

        embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
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

    private String extension(DocumentType documentType) {
        return switch (documentType) {
            case PDF -> "pdf";
            case DOCX -> "docx";
            case MD -> "md";
            case HTML -> "html";
            default -> "txt";
        };
    }

    private String contentType(DocumentType documentType) {
        return switch (documentType) {
            case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case MD -> "text/markdown";
            case HTML -> "text/html";
            default -> "text/plain";
        };
    }

    private DocumentChunkDraft draft(int index, String text, int start, int end) {
        return new DocumentChunkDraft(
            index,
            text,
            1,
            start,
            end,
            null,
            null,
            "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c",
            null
        );
    }

    private DocumentChunkDraft draftWithSource() {
        return new DocumentChunkDraft(
            0,
            "본문",
            1,
            0,
            2,
            2,
            "Section",
            "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c",
            "{\"source\":\"pdf\"}"
        );
    }
}
