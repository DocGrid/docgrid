package com.opensource.docgrid.domain.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;

/**
 * 정합성 검사가 현재 Version만 Vector 대상으로 삼고 안전한 부분 누락만 복구 가능으로 분류하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncConsistencyInspector 단위 테스트")
class SyncConsistencyInspectorTest {

    private static final LocalDateTime INSPECTED_AT = LocalDateTime.of(2026, 8, 13, 20, 0);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private DocumentChunkRepository documentChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;

    private SyncConsistencyInspector inspector;
    private EmbeddingModel model;

    @BeforeEach
    void setUp() {
        SyncReconciliationProperties properties = new SyncReconciliationProperties();
        properties.setStalledThreshold(Duration.ofMinutes(15));
        inspector = new SyncConsistencyInspector(
            embeddingJobRepository,
            documentChunkRepository,
            embeddingRepository,
            properties
        );
        model = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(model, "id", 7L);
    }

    @Test
    @DisplayName("현재 Version의 기존 ACTIVE Vector는 보존하고 실제 누락 행만 자동복구 대상으로 분류한다")
    void inspect_marksPartialMissingEmbeddingsRepairable() {
        DocumentVersion version = indexedCurrentVersion(11L);
        given(documentChunkRepository.countByDocumentVersionId(11L)).willReturn(3L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            11L, 7L, EmbeddingStatus.ACTIVE
        )).willReturn(1L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(11L, 7L)).willReturn(1L);
        given(embeddingRepository.countByDocumentVersionId(11L)).willReturn(1L);

        List<SyncConsistencyObservation> observations = inspector.inspect(version, model, INSPECTED_AT);

        assertThat(observations)
            .singleElement()
            .satisfies(observation -> {
                assertThat(observation.issueType()).isEqualTo(SyncConsistencyIssueType.MISSING_EMBEDDINGS);
                assertThat(observation.repairable()).isTrue();
            });
    }

    @Test
    @DisplayName("행은 모두 있으나 ACTIVE 상태가 깨진 Vector Set은 보고만 한다")
    void inspect_reportsStatusDamageWithoutAutomaticRepair() {
        DocumentVersion version = indexedCurrentVersion(12L);
        given(documentChunkRepository.countByDocumentVersionId(12L)).willReturn(3L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            12L, 7L, EmbeddingStatus.ACTIVE
        )).willReturn(1L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(12L, 7L)).willReturn(3L);
        given(embeddingRepository.countByDocumentVersionId(12L)).willReturn(3L);

        List<SyncConsistencyObservation> observations = inspector.inspect(version, model, INSPECTED_AT);

        assertThat(observations)
            .singleElement()
            .satisfies(observation -> {
                assertThat(observation.repairable()).isFalse();
                assertThat(observation.actualJson()).isEqualTo(
                    "{\"activeEmbeddingCount\":1,\"currentModelEmbeddingCount\":3,"
                        + "\"allModelEmbeddingCount\":3}"
                );
            });
    }

    @Test
    @DisplayName("과거 INDEXED Version의 STALE Vector는 현재 검색 상태 불일치로 오인하지 않는다")
    void inspect_ignoresHistoricalIndexedVersionVectors() {
        Document document = document(3L, DocumentStatus.INDEXED);
        DocumentVersion historical = version(document, 13L, DocumentVersionStatus.INDEXED);
        DocumentVersion current = version(document, 14L, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(current);
        given(documentChunkRepository.countByDocumentVersionId(13L)).willReturn(3L);

        assertThat(inspector.inspect(historical, model, INSPECTED_AT)).isEmpty();
    }

    @Test
    @DisplayName("정상 삭제로 Vector가 모두 STALE이 된 문서는 Vector 누락으로 보고하지 않는다")
    void inspect_ignoresStaleVectorsOfDeletedDocument() {
        DocumentVersion version = deletedCurrentVersion(15L);
        given(documentChunkRepository.countByDocumentVersionId(15L)).willReturn(5L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            15L, 7L, EmbeddingStatus.ACTIVE
        )).willReturn(0L);
        given(embeddingRepository.countByDocumentIdAndStatus(15L, EmbeddingStatus.ACTIVE)).willReturn(0L);
        // 삭제 문서는 Vector 완전성 검사 대상이 아니므로 아래 전체 개수는 조회되지 않아야 한다.
        lenient().when(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(15L, 7L)).thenReturn(5L);
        lenient().when(embeddingRepository.countByDocumentVersionId(15L)).thenReturn(5L);

        assertThat(inspector.inspect(version, model, INSPECTED_AT)).isEmpty();
    }

    @Test
    @DisplayName("삭제 직후 일부 Vector만 ACTIVE로 남아도 삭제 잔여물만 보고하고 자동복구 대상으로 분류하지 않는다")
    void inspect_reportsOnlyResidueForDeletedDocumentWithPartialActiveVectors() {
        DocumentVersion version = deletedCurrentVersion(16L);
        given(documentChunkRepository.countByDocumentVersionId(16L)).willReturn(5L);
        given(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
            16L, 7L, EmbeddingStatus.ACTIVE
        )).willReturn(4L);
        given(embeddingRepository.countByDocumentIdAndStatus(16L, EmbeddingStatus.ACTIVE)).willReturn(4L);
        // 삭제 문서는 Vector 완전성 검사 대상이 아니므로 아래 전체 개수는 조회되지 않아야 한다.
        lenient().when(embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(16L, 7L)).thenReturn(4L);
        lenient().when(embeddingRepository.countByDocumentVersionId(16L)).thenReturn(4L);

        List<SyncConsistencyObservation> observations = inspector.inspect(version, model, INSPECTED_AT);

        assertThat(observations)
            .singleElement()
            .satisfies(observation -> {
                assertThat(observation.issueType()).isEqualTo(SyncConsistencyIssueType.DELETED_DOCUMENT_RESIDUE);
                assertThat(observation.repairable()).isFalse();
            });
    }

    private DocumentVersion indexedCurrentVersion(Long versionId) {
        Document document = document(versionId, DocumentStatus.INDEXED);
        DocumentVersion version = version(document, versionId, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(version);
        return version;
    }

    private DocumentVersion deletedCurrentVersion(Long versionId) {
        Document document = document(versionId, DocumentStatus.DELETED);
        DocumentVersion version = version(document, versionId, DocumentVersionStatus.INDEXED);
        document.updateCurrentVersion(version);
        return version;
    }

    private Document document(Long id, DocumentStatus status) {
        Document document = Document.builder()
            .title("정합성 검사 문서")
            .documentType(DocumentType.PDF)
            .sourceType(DocumentSourceType.UPLOAD)
            .status(status)
            .visibility(VisibilityType.PRIVATE)
            .build();
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    private DocumentVersion version(
        Document document,
        Long id,
        DocumentVersionStatus status
    ) {
        DocumentVersion version = DocumentVersion.builder()
            .document(document)
            .versionNo(1)
            .status(status)
            .build();
        ReflectionTestUtils.setField(version, "id", id);
        ReflectionTestUtils.setField(version, "updatedAt", INSPECTED_AT.minusMinutes(1));
        return version;
    }
}
