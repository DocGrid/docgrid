package com.opensource.docgrid.domain.document.service.query;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.converter.DocumentDetailConverter;
import com.opensource.docgrid.domain.document.converter.DocumentStatusConverter;
import com.opensource.docgrid.domain.document.converter.DocumentSummaryConverter;
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
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentStatusProjection;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 권한을 적용해 문서 목록·상세·버전 이력·추출 본문·원본 파일 Snapshot·인덱싱 상태를 조회한다.
 *
 * <p>모든 Entity 접근을 읽기 전용 트랜잭션 안에서 DTO 또는 불변 Snapshot으로 변환해 Lazy 연관관계가
 * 외부 계층으로 새지 않게 한다. 삭제 문서는 존재하지 않는 것처럼 처리하고, 접근 가능성은 검색과 같은
 * 권한 계산을 사용한다. 실제 파일 저장소 I/O는 {@code DocumentFileService}가 트랜잭션 밖에서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentQueryService {

    private static final Set<DocumentVersionStatus> PROCESSING_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final Set<EmbeddingJobStatus> ACTIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );
    private static final Set<DocumentVersionStatus> CONTENT_EXPECTED_VERSION_STATUSES = EnumSet.of(
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING,
        DocumentVersionStatus.INDEXED
    );
    // 목록에는 삭제된 문서만 빼고 모두 노출한다. 인덱싱 중·실패한 문서도 진행 상황 확인 대상이다.
    private static final List<String> LISTABLE_STATUSES = EnumSet.complementOf(
        EnumSet.of(DocumentStatus.DELETED)
    ).stream().map(DocumentStatus::name).toList();
    private static final Sort DOCUMENT_SORT = Sort.by(
        Sort.Order.desc("createdAt"),
        Sort.Order.desc("id")
    );

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final PrimaryDocumentPermissionQueryService permissionQueryService;
    private final DocumentDetailConverter documentDetailConverter;
    private final DocumentStatusConverter documentStatusConverter;
    private final DocumentSummaryConverter documentSummaryConverter;
    private final DocumentVersionHistoryConverter documentVersionHistoryConverter;

    /**
     * 사용자가 읽을 수 있는 문서를 선택한 상태와 페이지 조건으로 조회한다.
     *
     * <p>상태를 생략하면 삭제 문서를 제외하되 처리 중·실패 문서도 진행 확인을 위해 포함한다.
     */
    public PageResponse<DocumentSummaryResponse> getMyDocuments(
        Long userId,
        DocumentStatus status,
        int page,
        int size
    ) {
        // 1. 검색과 같은 권한 pre-filter로 읽을 수 있는 문서 ID를 먼저 좁힌다.
        List<String> statuses = status == null ? LISTABLE_STATUSES : List.of(status.name());
        List<Long> readableDocumentIds = documentRepository.findReadableDocumentIds(userId, statuses);
        Pageable pageable = PageRequest.of(page, size, DOCUMENT_SORT);
        if (readableDocumentIds.isEmpty()) {
            return PageResponse.from(Page.empty(pageable), List.of());
        }

        // 2. Entity가 Transaction 밖으로 나가기 전에 DTO로 변환한다.
        Page<Document> documents = documentRepository.findAllByIdIn(readableDocumentIds, pageable);
        List<DocumentSummaryResponse> content = documents.getContent().stream()
            .map(documentSummaryConverter::toResponse)
            .toList();
        return PageResponse.from(documents, content);
    }

    /**
     * 읽기 가능한 문서의 Metadata와 현재 버전 요약을 반환한다.
     */
    // 최종 인가와 문서 조회가 같은 Primary 연결을 사용하도록 read-only 라우팅을 피한다.
    @Transactional
    public DocumentDetailResponse getDocumentDetail(Long userId, Long documentId) {
        // 1. 권한과 삭제 상태를 검증한 상세 조회용 Entity를 가져온다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();

        // 2. 현재 버전에 저장된 Chunk Set이 있는지 별도 응답 상태로 계산한다.
        boolean contentAvailable = currentVersion != null
            && documentChunkRepository.existsByDocumentVersionId(currentVersion.getId());
        return documentDetailConverter.toResponse(document, contentAvailable);
    }

    /**
     * 읽기 가능한 문서의 모든 버전과 각 버전의 최신 인덱싱 Job을 타임라인 순서로 반환한다.
     */
    // 최종 인가와 문서 조회가 같은 Primary 연결을 사용하도록 read-only 라우팅을 피한다.
    @Transactional
    public List<DocumentVersionHistoryResponse> getDocumentVersions(Long userId, Long documentId) {
        // 1. 상세 조회와 같은 권한·삭제 정책으로 타임라인 대상 문서를 확정한다.
        Document document = getReadableDocument(userId, documentId);
        List<DocumentVersion> versions = documentVersionRepository.findHistoryByDocumentId(documentId);
        if (versions.isEmpty()) {
            return List.of();
        }

        // 2. Version별 최신 Job을 한 번에 읽고 ID 역순의 첫 Job만 Snapshot으로 선택한다.
        List<Long> versionIds = versions.stream().map(DocumentVersion::getId).toList();
        Map<Long, EmbeddingJob> latestJobsByVersionId = new HashMap<>();
        for (EmbeddingJob job : embeddingJobRepository.findHistoryJobsByDocumentVersionIds(versionIds)) {
            latestJobsByVersionId.putIfAbsent(job.getDocumentVersion().getId(), job);
        }

        // 3. current_version_id를 기준으로 정상 v1과 실패 v2 같은 Fallback 상태를 명확히 표시한다.
        Long currentVersionId = document.getCurrentVersion() != null ? document.getCurrentVersion().getId() : null;
        return versions.stream()
            .map(version -> documentVersionHistoryConverter.toResponse(
                version,
                currentVersionId,
                latestJobsByVersionId.get(version.getId())
            ))
            .toList();
    }

    /**
     * 현재 버전의 Chunk를 검증하고 중복을 제거해 정규화 Text 전체를 복원한다.
     */
    // 최종 인가와 문서 조회가 같은 Primary 연결을 사용하도록 read-only 라우팅을 피한다.
    @Transactional
    public DocumentContentResponse getDocumentContent(Long userId, Long documentId) {
        // 1. 읽기 가능한 문서와 현재 버전을 확정한다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();
        if (currentVersion == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_NOT_AVAILABLE);
        }

        // 2. 저장 순서가 아니라 불변 Chunk Index 순서로 전체 Chunk Set을 조회한다.
        List<DocumentChunk> chunks = documentChunkRepository
            .findAllByDocumentVersionIdOrderByChunkIndexAsc(currentVersion.getId());
        if (chunks.isEmpty()) {
            if (CONTENT_EXPECTED_VERSION_STATUSES.contains(currentVersion.getStatus())) {
                throw inconsistentChunks(currentVersion.getId(), "본문이 있어야 하는 상태지만 Chunk가 없습니다.");
            }
            throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_NOT_AVAILABLE);
        }

        // 3. 전역 Code Point Offset으로 중첩 구간과 Segment 사이 줄바꿈을 복원한다.
        String content = restoreContent(currentVersion.getId(), chunks);
        return new DocumentContentResponse(
            document.getId(),
            currentVersion.getId(),
            currentVersion.getVersionNo(),
            content,
            chunks.size()
        );
    }

    /**
     * 원본 파일 I/O를 Transaction 밖에서 수행할 수 있도록 현재 버전의 저장 위치만 Snapshot으로 반환한다.
     */
    // 최종 인가와 문서 조회가 같은 Primary 연결을 사용하도록 read-only 라우팅을 피한다.
    @Transactional
    public DocumentFileSnapshot getDocumentFileSnapshot(Long userId, Long documentId) {
        // 1. 동일한 읽기 권한과 삭제 문서 차단 규칙을 원본 파일에도 적용한다.
        Document document = getReadableDocument(userId, documentId);
        DocumentVersion currentVersion = document.getCurrentVersion();
        FileObject fileObject = currentVersion != null ? currentVersion.getFileObject() : null;
        if (currentVersion == null || fileObject == null) {
            log.error("문서 현재 버전의 원본 파일 참조가 없습니다. documentId={}", documentId);
            throw new DocGridException(ErrorCode.DOCUMENT_FILE_REFERENCE_MISSING);
        }

        // 2. JPA Entity 대신 외부 저장소 조회에 필요한 불변 값만 Transaction 밖으로 전달한다.
        return new DocumentFileSnapshot(
            new StoredFile(
                fileObject.getStorageProvider(),
                fileObject.getBucketName(),
                fileObject.getObjectKey()
            ),
            currentVersion.getOriginalFilename() != null
                ? currentVersion.getOriginalFilename()
                : fileObject.getOriginalFilename(),
            currentVersion.getContentType() != null
                ? currentVersion.getContentType()
                : fileObject.getContentType(),
            fileObject.getFileSize()
        );
    }

    /**
     * 문서의 현재 검색 버전과 별도로 처리 중인 버전·Job 상태를 일관된 Projection으로 조회한다.
     */
    // 최종 인가와 문서 조회가 같은 Primary 연결을 사용하도록 read-only 라우팅을 피한다.
    @Transactional
    public DocumentStatusResponse getDocumentStatus(Long userId, Long documentId) {
        // 1. 상태 정보도 문서 본문과 같은 읽기 권한 경계를 적용한다.
        if (!permissionQueryService.canReadDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 현재 버전과 진행 버전을 한 Query 결과로 조회하고 행 수·필드 조합의 일관성을 확인한다.
        List<DocumentStatusProjection> rows = documentRepository.findDocumentStatus(
            documentId,
            PROCESSING_VERSION_STATUSES,
            ACTIVE_JOB_STATUSES
        );
        if (rows.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (rows.size() != 1 || isInconsistent(rows.get(0))) {
            log.error("문서 인덱싱 상태가 일관되지 않습니다. documentId={}, rowCount={}", documentId, rows.size());
            throw new DocGridException(ErrorCode.INDEXING_STATUS_INCONSISTENT);
        }

        // 3. 삭제 문서는 존재하지 않는 것으로 숨기고 검증된 Projection만 외부 DTO로 변환한다.
        DocumentStatusProjection projection = rows.get(0);
        if (projection.getDocumentStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return documentStatusConverter.toResponse(projection);
    }

    /**
     * 읽기 권한과 삭제 상태를 공통 검증하고 현재 버전을 함께 조회한 문서 Entity를 반환한다.
     */
    private Document getReadableDocument(Long userId, Long documentId) {
        if (!permissionQueryService.canReadDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 현재 버전만 JOIN FETCH하고 소유자·파일은 이 읽기 전용 Transaction 안에서 필요할 때 로딩한다.
        Document document = documentRepository.findByIdWithCurrentVersion(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    /**
     * 순서와 Code Point Offset이 검증된 Chunk에서 겹치는 구간을 제거해 원래 정규화 본문을 복원한다.
     *
     * <p>Chunk 범위는 UTF-16 char가 아니라 Unicode Code Point 기준이다. 연속 Segment 사이에는
     * 정규화 과정에서 제거된 LF 한 칸만 복원하며, 그보다 큰 공백이나 역방향 범위는 데이터 손상으로 본다.
     */
    private String restoreContent(Long documentVersionId, List<DocumentChunk> chunks) {
        StringBuilder restored = new StringBuilder();
        int previousEnd = 0;

        for (int expectedIndex = 0; expectedIndex < chunks.size(); expectedIndex++) {
            DocumentChunk chunk = chunks.get(expectedIndex);
            int start = chunk.getCharStart();
            int end = chunk.getCharEnd();
            int[] codePoints = chunk.getChunkText().codePoints().toArray();

            // 1. Index와 반열린 Code Point 범위가 Chunk Text와 일치해야 안전하게 중복을 제거할 수 있다.
            if (chunk.getChunkIndex() != expectedIndex
                || start < 0
                || end <= start
                || end - start != codePoints.length
                || (expectedIndex == 0 && start != 0)) {
                throw inconsistentChunks(documentVersionId, "Chunk Index 또는 Offset이 올바르지 않습니다.");
            }

            // 2. Segment 사이는 의도된 LF 한 칸만 허용하고 그보다 큰 유실 구간은 데이터 불일치로 차단한다.
            if (start > previousEnd + 1 || end <= previousEnd) {
                throw inconsistentChunks(documentVersionId, "Chunk 사이에 복원할 수 없는 범위가 있습니다.");
            }
            if (start == previousEnd + 1) {
                restored.append('\n');
            }

            // 3. 이전 Chunk가 이미 포함한 Code Point만 건너뛰고 새 구간을 이어 붙인다.
            int overlap = Math.max(0, previousEnd - start);
            restored.append(new String(codePoints, overlap, codePoints.length - overlap));
            previousEnd = end;
        }
        return restored.toString();
    }

    /**
     * Chunk 복원 불변식 위반을 내부 식별자와 제한된 사유로 기록하고 공통 도메인 예외를 생성한다.
     */
    private DocGridException inconsistentChunks(Long documentVersionId, String reason) {
        log.error("문서 본문 Chunk 데이터가 일관되지 않습니다. documentVersionId={}, reason={}",
            documentVersionId, reason);
        return new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
    }

    /**
     * 상태 Projection의 현재 버전 필드와 처리 중 버전·Job 필드가 각각 함께 존재하는지 검사한다.
     */
    private boolean isInconsistent(DocumentStatusProjection projection) {
        boolean hasCurrentVersionNo = projection.getCurrentVersionNo() != null;
        boolean hasCurrentVersionStatus = projection.getCurrentVersionStatus() != null;
        if (hasCurrentVersionNo != hasCurrentVersionStatus) {
            return true;
        }

        boolean hasProcessingVersionNo = projection.getProcessingVersionNo() != null;
        boolean hasProcessingVersionStatus = projection.getProcessingVersionStatus() != null;
        boolean hasProcessingJobStatus = projection.getProcessingJobStatus() != null;
        return hasProcessingVersionNo != hasProcessingVersionStatus
            || hasProcessingVersionNo != hasProcessingJobStatus;
    }
}
