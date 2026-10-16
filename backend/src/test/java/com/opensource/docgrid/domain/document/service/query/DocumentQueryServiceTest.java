package com.opensource.docgrid.domain.document.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.document.converter.DocumentStatusConverter;
import com.opensource.docgrid.domain.document.converter.DocumentSummaryConverter;
import com.opensource.docgrid.domain.document.converter.DocumentDetailConverter;
import com.opensource.docgrid.domain.document.converter.DocumentVersionHistoryConverter;
import com.opensource.docgrid.domain.document.dto.response.DocumentContentResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionHistoryResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentQueryService 테스트")
class DocumentQueryServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long DOCUMENT_ID = 20L;

    @InjectMocks
    private DocumentQueryService service;

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private PrimaryDocumentPermissionQueryService permissionQueryService;
    @Mock private DocumentDetailConverter documentDetailConverter;
    @Mock private DocumentStatusConverter documentStatusConverter;
    @Mock private DocumentSummaryConverter documentSummaryConverter;
    @Mock private DocumentVersionHistoryConverter documentVersionHistoryConverter;
    @Mock private DocumentStatusProjection projection;

    @Test
    @DisplayName("읽기 가능한 문서의 상세 정보와 본문 조회 가능 여부를 반환한다")
    void getDocumentDetail_returnsResponseWithContentAvailability() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        DocumentDetailResponse expected = mock(DocumentDetailResponse.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(30L);
        given(documentChunkRepository.existsByDocumentVersionId(30L)).willReturn(true);
        given(documentDetailConverter.toResponse(document, true)).willReturn(expected);

        DocumentDetailResponse result = service.getDocumentDetail(USER_ID, DOCUMENT_ID);

        assertThat(result).isSameAs(expected);
        then(documentDetailConverter).should().toResponse(document, true);
    }

    @Test
    @DisplayName("문서 읽기 권한이 없으면 상세 Entity를 조회하지 않고 403 예외가 발생한다")
    void getDocumentDetail_throws_whenReadPermissionIsDenied() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getDocumentDetail(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentRepository).should(never()).findByIdWithCurrentVersion(DOCUMENT_ID);
    }

    @Test
    @DisplayName("전체 버전을 최신순으로 각 버전의 최신 Job과 결합해 반환한다")
    void getDocumentVersions_returnsCompleteTimeline() {
        Document document = mock(Document.class);
        DocumentVersion version2 = mock(DocumentVersion.class);
        DocumentVersion version1 = mock(DocumentVersion.class);
        EmbeddingJob version2Job = mock(EmbeddingJob.class);
        DocumentVersionHistoryResponse expectedVersion2 = mock(DocumentVersionHistoryResponse.class);
        DocumentVersionHistoryResponse expectedVersion1 = mock(DocumentVersionHistoryResponse.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(version1);
        given(version1.getId()).willReturn(30L);
        given(version2.getId()).willReturn(31L);
        given(version2Job.getDocumentVersion()).willReturn(version2);
        given(documentVersionRepository.findHistoryByDocumentId(DOCUMENT_ID))
            .willReturn(List.of(version2, version1));
        given(embeddingJobRepository.findHistoryJobsByDocumentVersionIds(List.of(31L, 30L)))
            .willReturn(List.of(version2Job));
        given(documentVersionHistoryConverter.toResponse(version2, 30L, version2Job))
            .willReturn(expectedVersion2);
        given(documentVersionHistoryConverter.toResponse(version1, 30L, null))
            .willReturn(expectedVersion1);

        List<DocumentVersionHistoryResponse> result = service.getDocumentVersions(USER_ID, DOCUMENT_ID);

        assertThat(result).containsExactly(expectedVersion2, expectedVersion1);
    }

    @Test
    @DisplayName("문서 읽기 권한이 없으면 버전 이력 저장소를 조회하지 않는다")
    void getDocumentVersions_throws_whenReadPermissionIsDenied() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getDocumentVersions(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentVersionRepository).should(never()).findHistoryByDocumentId(DOCUMENT_ID);
    }

    @Test
    @DisplayName("Chunk 중첩과 Segment 경계를 제거해 Unicode 전체 본문을 복원한다")
    void getDocumentContent_restoresOverlapsAndSegmentLineFeed() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        givenReadableDocument(document);
        given(document.getId()).willReturn(DOCUMENT_ID);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(30L);
        given(currentVersion.getVersionNo()).willReturn(2);
        List<DocumentChunk> chunks = List.of(
            chunk(0, "가나다라", 0, 4),
            chunk(1, "다라마바", 2, 6),
            chunk(2, "서울😀", 7, 10)
        );
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(30L))
            .willReturn(chunks);

        DocumentContentResponse result = service.getDocumentContent(USER_ID, DOCUMENT_ID);

        assertThat(result.content()).isEqualTo("가나다라마바\n서울😀");
        assertThat(result.documentVersionId()).isEqualTo(30L);
        assertThat(result.versionNo()).isEqualTo(2);
        assertThat(result.chunkCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("본문 생성 전 상태에 Chunk가 없으면 조회 준비 전 예외가 발생한다")
    void getDocumentContent_throws_whenContentIsNotAvailable() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(30L);
        given(currentVersion.getStatus()).willReturn(DocumentVersionStatus.PARSING);
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(30L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getDocumentContent(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_CONTENT_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("INDEXED 버전에 Chunk가 없으면 데이터 불일치 예외가 발생한다")
    void getDocumentContent_throws_whenIndexedChunksAreMissing() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(30L);
        given(currentVersion.getStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(30L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getDocumentContent(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
    }

    @Test
    @DisplayName("Chunk Offset이 Text 길이와 다르면 데이터 불일치 예외가 발생한다")
    void getDocumentContent_throws_whenChunkOffsetsAreInvalid() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getId()).willReturn(30L);
        DocumentChunk invalidChunk = chunk(0, "본문", 0, 3);
        given(documentChunkRepository.findAllByDocumentVersionIdOrderByChunkIndexAsc(30L))
            .willReturn(List.of(invalidChunk));

        assertThatThrownBy(() -> service.getDocumentContent(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
    }

    @Test
    @DisplayName("읽기 가능한 문서의 현재 버전 원본 위치를 Snapshot으로 반환한다")
    void getDocumentFileSnapshot_returnsCurrentVersionFileMetadata() {
        Document document = mock(Document.class);
        DocumentVersion currentVersion = mock(DocumentVersion.class);
        FileObject fileObject = mock(FileObject.class);
        givenReadableDocument(document);
        given(document.getCurrentVersion()).willReturn(currentVersion);
        given(currentVersion.getFileObject()).willReturn(fileObject);
        given(currentVersion.getOriginalFilename()).willReturn("guide.pdf");
        given(currentVersion.getContentType()).willReturn("application/pdf");
        given(fileObject.getStorageProvider()).willReturn(StorageProvider.MINIO);
        given(fileObject.getBucketName()).willReturn("documents");
        given(fileObject.getObjectKey()).willReturn("objects/guide.pdf");
        given(fileObject.getFileSize()).willReturn(100L);

        DocumentFileSnapshot result = service.getDocumentFileSnapshot(USER_ID, DOCUMENT_ID);

        assertThat(result.storedFile()).isEqualTo(
            new StoredFile(StorageProvider.MINIO, "documents", "objects/guide.pdf")
        );
        assertThat(result.originalFilename()).isEqualTo("guide.pdf");
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.fileSize()).isEqualTo(100L);
    }

    @Test
    @DisplayName("읽을 수 있는 문서를 페이지 응답으로 변환해 반환한다")
    void getMyDocuments_returnsPage_when_readableDocumentsExist() {
        Document document = mock(Document.class);
        DocumentSummaryResponse expected = summaryResponse();
        given(documentRepository.findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), anyCollection()
        )).willReturn(List.of(DOCUMENT_ID));
        given(documentRepository.findAllByIdIn(
            org.mockito.ArgumentMatchers.eq(List.of(DOCUMENT_ID)),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(document), PageRequest.of(0, 20), 1));
        given(documentSummaryConverter.toResponse(document)).willReturn(expected);

        PageResponse<DocumentSummaryResponse> result = service.getMyDocuments(USER_ID, null, 0, 20);

        assertThat(result.content()).containsExactly(expected);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.first()).isTrue();
    }

    @Test
    @DisplayName("읽을 수 있는 문서가 없으면 문서를 조회하지 않고 빈 페이지를 반환한다")
    void getMyDocuments_returnsEmptyPage_when_noReadableDocument() {
        given(documentRepository.findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), anyCollection()
        )).willReturn(List.of());

        PageResponse<DocumentSummaryResponse> result = service.getMyDocuments(USER_ID, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        then(documentRepository).should(never()).findAllByIdIn(anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("status를 지정하지 않으면 DELETED를 제외한 전체 상태로 조회한다")
    void getMyDocuments_excludesDeletedStatus_when_statusIsNotGiven() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        given(documentRepository.findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), anyCollection()
        )).willReturn(List.of());

        service.getMyDocuments(USER_ID, null, 0, 20);

        then(documentRepository).should().findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture()
        );
        assertThat(captor.getValue())
            .contains(DocumentStatus.INDEXED.name(), DocumentStatus.INDEXING.name(), DocumentStatus.FAILED.name())
            .doesNotContain(DocumentStatus.DELETED.name());
    }

    @Test
    @DisplayName("status를 지정하면 해당 상태만으로 조회한다")
    void getMyDocuments_usesGivenStatus_when_statusIsGiven() {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.captor();
        given(documentRepository.findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), anyCollection()
        )).willReturn(List.of());

        service.getMyDocuments(USER_ID, DocumentStatus.FAILED, 0, 20);

        then(documentRepository).should().findReadableDocumentIds(
            org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture()
        );
        assertThat(captor.getValue()).containsExactly(DocumentStatus.FAILED.name());
    }

    @Test
    @DisplayName("읽기 권한이 있고 상태가 일관되면 문서 상태를 반환한다")
    void getDocumentStatus_returnsResponse_when_statusIsConsistent() {
        DocumentStatusResponse expected = new DocumentStatusResponse(
            DOCUMENT_ID, DocumentStatus.INDEXED, null, null
        );
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getCurrentVersionNo()).willReturn(1);
        given(projection.getCurrentVersionStatus()).willReturn(DocumentVersionStatus.INDEXED);
        given(projection.getProcessingVersionNo()).willReturn(null);
        given(projection.getProcessingVersionStatus()).willReturn(null);
        given(projection.getProcessingJobStatus()).willReturn(null);
        given(documentStatusConverter.toResponse(projection)).willReturn(expected);

        DocumentStatusResponse result = service.getDocumentStatus(USER_ID, DOCUMENT_ID);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("문서 읽기 권한이 없으면 상태를 조회하지 않고 403 예외가 발생한다")
    void getDocumentStatus_throws_when_readPermissionIsDenied() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentRepository).should(never()).findDocumentStatus(
            org.mockito.ArgumentMatchers.anyLong(), anyCollection(), anyCollection()
        );
    }

    @Test
    @DisplayName("상태 조회 결과가 없으면 문서 없음 예외가 발생한다")
    void getDocumentStatus_throws_when_documentDoesNotExist() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of());

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("처리 중 버전에 활성 Job이 없으면 상태 불일치 예외가 발생한다")
    void getDocumentStatus_throws_when_processingJobIsMissing() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getCurrentVersionNo()).willReturn(null);
        given(projection.getCurrentVersionStatus()).willReturn(null);
        given(projection.getProcessingVersionNo()).willReturn(2);
        given(projection.getProcessingVersionStatus()).willReturn(DocumentVersionStatus.PARSING);
        given(projection.getProcessingJobStatus()).willReturn(null);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INDEXING_STATUS_INCONSISTENT);
    }

    @Test
    @DisplayName("활성 Job이 중복되어 조회 행이 여러 개면 상태 불일치 예외가 발생한다")
    void getDocumentStatus_throws_when_multipleStatusRowsExist() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection, projection));

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INDEXING_STATUS_INCONSISTENT);
    }

    @Test
    @DisplayName("삭제된 문서는 문서 없음 예외가 발생한다")
    void getDocumentStatus_throws_when_documentIsDeleted() {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findDocumentStatus(
            org.mockito.ArgumentMatchers.eq(DOCUMENT_ID), anyCollection(), anyCollection()
        )).willReturn(List.of(projection));
        given(projection.getDocumentStatus()).willReturn(DocumentStatus.DELETED);
        given(projection.getCurrentVersionNo()).willReturn(null);
        given(projection.getCurrentVersionStatus()).willReturn(null);
        given(projection.getProcessingVersionNo()).willReturn(null);
        given(projection.getProcessingVersionStatus()).willReturn(null);
        given(projection.getProcessingJobStatus()).willReturn(null);

        assertThatThrownBy(() -> service.getDocumentStatus(USER_ID, DOCUMENT_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    private DocumentSummaryResponse summaryResponse() {
        return new DocumentSummaryResponse(
            DOCUMENT_ID,
            "문서 목록 테스트",
            null,
            DocumentType.TXT,
            DocumentStatus.INDEXED,
            VisibilityType.PRIVATE,
            USER_ID,
            "테스트유저",
            1,
            DocumentVersionStatus.INDEXED,
            null,
            null
        );
    }

    private void givenReadableDocument(Document document) {
        given(permissionQueryService.canReadDocument(USER_ID, DOCUMENT_ID)).willReturn(true);
        given(documentRepository.findByIdWithCurrentVersion(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(document.getStatus()).willReturn(DocumentStatus.INDEXED);
    }

    private DocumentChunk chunk(int index, String text, int start, int end) {
        DocumentChunk chunk = mock(DocumentChunk.class);
        given(chunk.getChunkIndex()).willReturn(index);
        given(chunk.getChunkText()).willReturn(text);
        given(chunk.getCharStart()).willReturn(start);
        given(chunk.getCharEnd()).willReturn(end);
        return chunk;
    }
}
