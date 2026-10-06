package com.opensource.docgrid.domain.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

// A담당자 영역 — B담당자는 존재 확인 등 읽기 전용으로만 사용
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 대시보드 집계 카드의 전체 문서 수. Soft-delete된 문서는 제외한다.
     */
    long countByDeletedAtIsNull();

    /**
     * 대시보드 집계 카드에서 특정 상태 하나에 속하는 문서 수를 센다 (예: 검색 가능 문서 수).
     */
    long countByStatus(DocumentStatus status);

    /**
     * 대시보드 집계 카드에서 여러 상태에 걸친 문서 수를 센다 (예: 인덱싱 대기 중 문서 수).
     */
    long countByStatusIn(Collection<DocumentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :documentId")
    Optional<Document> findByIdForUpdate(@Param("documentId") Long documentId);

    // currentVersion은 LAZY라, OSIV가 꺼진 경로(/mcp)에서 findById만 쓰면
    // 트랜잭션 종료 후 지연 로딩 시 LazyInitializationException이 난다. JOIN FETCH로 즉시 로딩한다.
    // 상세 조회는 이 쿼리 뒤의 읽기 전용 Transaction 안에서 owner와 fileObject를 개별 로딩해
    // 배포 데이터의 선택적 연관관계까지 한 번에 묶는 다중 JOIN FETCH를 피한다.
    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.currentVersion WHERE d.id = :documentId")
    Optional<Document> findByIdWithCurrentVersion(@Param("documentId") Long documentId);

    @Query("""
        SELECT d.id AS documentId,
               d.status AS documentStatus,
               cv.versionNo AS currentVersionNo,
               cv.status AS currentVersionStatus,
               pv.versionNo AS processingVersionNo,
               pv.status AS processingVersionStatus,
               ej.status AS processingJobStatus
        FROM Document d
        LEFT JOIN d.currentVersion cv
        LEFT JOIN DocumentVersion pv
               ON pv.document = d
              AND pv.status IN :processingVersionStatuses
        LEFT JOIN EmbeddingJob ej
               ON ej.documentVersion = pv
              AND ej.status IN :activeJobStatuses
        WHERE d.id = :documentId
        """)
    List<DocumentStatusProjection> findDocumentStatus(
        @Param("documentId") Long documentId,
        @Param("processingVersionStatuses") Collection<DocumentVersionStatus> processingVersionStatuses,
        @Param("activeJobStatuses") Collection<EmbeddingJobStatus> activeJobStatuses
    );

    // 공개 범위가 DEPARTMENT인 문서를 소유자와 같은 부서의 사용자가 읽을 수 있는지 판단한다.
    // 부서가 없는 소유자·사용자는 NULL 비교가 거짓이라 자연히 제외된다. 검색 pre-filter SQL의 같은 블록과 조건을 맞춘다.
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM documents d
              JOIN users owner ON owner.id = d.owner_user_id
              JOIN users viewer ON viewer.department_id = owner.department_id
            WHERE d.id = :documentId AND d.visibility = 'DEPARTMENT' AND viewer.id = :userId
        )
        """, nativeQuery = true)
    boolean existsDepartmentVisibleToUser(
        @Param("documentId") Long documentId,
        @Param("userId") Long userId
    );

    // 검색 pre-filter — 사용자가 읽을 수 있는 문서 ID 전체 (컬렉션 미지정)
    // 접근 경로: OWNER / PUBLIC / DEPARTMENT 공개 범위(소유자와 같은 부서) / USER캐시 / ROLE live / DEPT live (문서·컬렉션 권한 모두 포함)
    // 컬렉션 ROLE/DEPT 권한은 collection_closure(조상-자손 물질화 테이블)를 통해 부모 컬렉션 체인까지 상속된다.
    // statuses는 DocumentStatus.name() 문자열 목록. 검색은 INDEXED만, 문서 목록은 처리 중 상태까지 넘긴다.
    @Query(value = """
        SELECT d.id FROM documents d
        WHERE d.owner_user_id = :userId AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
        WHERE d.visibility = 'PUBLIC' AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN users owner ON owner.id = d.owner_user_id
          JOIN users viewer ON viewer.department_id = owner.department_id
        WHERE d.visibility = 'DEPARTMENT' AND viewer.id = :userId
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN user_document_access_cache c ON c.document_id = d.id
        WHERE c.user_id = :userId AND c.can_read = true AND c.invalidated_at IS NULL
          AND (c.expires_at IS NULL OR c.expires_at > NOW())
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN document_permissions dp ON dp.document_id = d.id
          JOIN user_roles ur ON ur.role_id = dp.role_id
        WHERE dp.target_type = 'ROLE' AND ur.user_id = :userId AND dp.can_read = true
          AND (dp.expires_at IS NULL OR dp.expires_at > NOW())
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN document_permissions dp ON dp.document_id = d.id
          JOIN users u ON u.department_id = dp.department_id
        WHERE dp.target_type = 'DEPARTMENT' AND u.id = :userId AND dp.can_read = true
          AND (dp.expires_at IS NULL OR dp.expires_at > NOW())
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN collection_documents cd ON cd.document_id = d.id
          JOIN collection_closure ca ON ca.descendant_id = cd.collection_id
          JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
          JOIN user_roles ur ON ur.role_id = cp.role_id
        WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
          AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        UNION
        SELECT d.id FROM documents d
          JOIN collection_documents cd ON cd.document_id = d.id
          JOIN collection_closure ca ON ca.descendant_id = cd.collection_id
          JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
          JOIN users u ON u.department_id = cp.department_id
        WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId AND cp.can_read = true
          AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
          AND d.deleted_at IS NULL AND d.status IN (:statuses)
        """, nativeQuery = true)
    List<Long> findReadableDocumentIds(
        @Param("userId") Long userId,
        @Param("statuses") Collection<String> statuses
    );

    // 검색 pre-filter — 특정 컬렉션 내에서 사용자가 읽을 수 있는 문서 ID
    // 컬렉션 ROLE/DEPT 권한은 collection_closure(조상-자손 물질화 테이블)를 통해 부모 컬렉션 체인까지 상속된다.
    // 바깥쪽 WHERE는 "이 컬렉션에 직접 속한 문서만" 필터 — 하위 폴더 문서가 상위 폴더 목록에 섞이지 않게 한다.
    @Query(value = """
        SELECT sub.id FROM (
            SELECT d.id FROM documents d
            WHERE d.owner_user_id = :userId AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
            WHERE d.visibility = 'PUBLIC' AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN users owner ON owner.id = d.owner_user_id
              JOIN users viewer ON viewer.department_id = owner.department_id
            WHERE d.visibility = 'DEPARTMENT' AND viewer.id = :userId
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN user_document_access_cache c ON c.document_id = d.id
            WHERE c.user_id = :userId AND c.can_read = true AND c.invalidated_at IS NULL
              AND (c.expires_at IS NULL OR c.expires_at > NOW())
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN document_permissions dp ON dp.document_id = d.id
              JOIN user_roles ur ON ur.role_id = dp.role_id
            WHERE dp.target_type = 'ROLE' AND ur.user_id = :userId AND dp.can_read = true
              AND (dp.expires_at IS NULL OR dp.expires_at > NOW())
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN document_permissions dp ON dp.document_id = d.id
              JOIN users u ON u.department_id = dp.department_id
            WHERE dp.target_type = 'DEPARTMENT' AND u.id = :userId AND dp.can_read = true
              AND (dp.expires_at IS NULL OR dp.expires_at > NOW())
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN collection_documents cd ON cd.document_id = d.id
              JOIN collection_closure ca ON ca.descendant_id = cd.collection_id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN user_roles ur ON ur.role_id = cp.role_id
            WHERE cp.target_type = 'ROLE' AND ur.user_id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
            UNION
            SELECT d.id FROM documents d
              JOIN collection_documents cd ON cd.document_id = d.id
              JOIN collection_closure ca ON ca.descendant_id = cd.collection_id
              JOIN collection_permissions cp ON cp.collection_id = ca.ancestor_id
              JOIN users u ON u.department_id = cp.department_id
            WHERE cp.target_type = 'DEPARTMENT' AND u.id = :userId AND cp.can_read = true
              AND (cp.expires_at IS NULL OR cp.expires_at > NOW())
              AND d.deleted_at IS NULL AND d.status IN (:statuses)
        ) sub
        WHERE sub.id IN (
            SELECT cd_filter.document_id FROM collection_documents cd_filter
            WHERE cd_filter.collection_id = :collectionId
        )
        """, nativeQuery = true)
    List<Long> findReadableDocumentIdsInCollection(
        @Param("userId") Long userId,
        @Param("collectionId") Long collectionId,
        @Param("statuses") Collection<String> statuses
    );

    // 목록 화면용 — 권한 pre-filter로 걸러진 ID를 받아 정렬·페이징만 담당한다.
    // currentVersion·owner는 LAZY라 버전 번호·상태·소유자 이름을 응답에 담으려면 JOIN FETCH가 필요하다.
    @Query(
        value = """
            SELECT d
            FROM Document d
            LEFT JOIN FETCH d.currentVersion
            JOIN FETCH d.owner
            WHERE d.id IN :documentIds
            """,
        countQuery = """
            SELECT COUNT(d)
            FROM Document d
            WHERE d.id IN :documentIds
            """
    )
    Page<Document> findAllByIdIn(@Param("documentIds") Collection<Long> documentIds, Pageable pageable);
}
