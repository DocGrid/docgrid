package com.opensource.docgrid.domain.permission.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.permission.entity.UserDocumentAccessCache;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;

public interface UserDocumentAccessCacheRepository extends JpaRepository<UserDocumentAccessCache, Long> {

    /**
     * 그룹 1 — 단건 조회 (1개)
     */

    // 특정 권한 출처(sourceType + sourceId)의 캐시 단건 조회
    Optional<UserDocumentAccessCache> findByUserIdAndDocumentIdAndSourceTypeAndSourceId(
            Long userId, Long documentId, AccessSourceType sourceType, Long sourceId);

    /**
     * 그룹 2 — {@code @Modifying} 벌크 쓰기 (3개)
     *
     * <p>캐시를 직접 INSERT/UPDATE하는 유일한 그룹. 벌크 UPDATE는 영속성 컨텍스트를 거치지
     * 않고 DB를 직접 치므로 clearAutomatically=true로 stale 엔티티를 방지한다.
     */

    // 특정 권한 출처에서 파생된 캐시 전체 무효화 (invalidated_at 일괄 설정)
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.invalidatedAt = CURRENT_TIMESTAMP
            WHERE c.sourceType = :sourceType AND c.sourceId = :sourceId AND c.invalidatedAt IS NULL
            """)
    int bulkInvalidateBySource(@Param("sourceType") AccessSourceType sourceType,
                               @Param("sourceId") Long sourceId);

    // 복수 출처에서 파생된 특정 문서 캐시 일괄 무효화 (컬렉션에서 문서 제거 시)
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.invalidatedAt = CURRENT_TIMESTAMP
            WHERE c.sourceType = :sourceType AND c.sourceId IN :sourceIds
              AND c.document.id = :documentId AND c.invalidatedAt IS NULL
            """)
    int bulkInvalidateBySourceIdsAndDocument(@Param("sourceType") AccessSourceType sourceType,
                                             @Param("sourceIds") List<Long> sourceIds,
                                             @Param("documentId") Long documentId);

    // 특정 권한 출처에서 파생된 캐시 전체 권한 갱신
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserDocumentAccessCache c
            SET c.canRead = :canRead, c.canWrite = :canWrite, c.canAdmin = :canAdmin,
                c.invalidatedAt = NULL, c.computedAt = CURRENT_TIMESTAMP, c.expiresAt = :expiresAt
            WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
            """)
    int bulkUpdateBySource(@Param("userId") Long userId,
                           @Param("sourceType") AccessSourceType sourceType,
                           @Param("sourceId") Long sourceId,
                           @Param("canRead") boolean canRead,
                           @Param("canWrite") boolean canWrite,
                           @Param("canAdmin") boolean canAdmin,
                           @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 그룹 3 — 벌크 INSERT 보조 조회 (1개)
     *
     * <p>bulkUpdateBySource로 기존 row를 갱신한 뒤, 여기서 "이미 캐시된 문서 ID"를 뽑아
     * 신규 문서만 걸러서 saveAll()로 INSERT한다(유니크 제약 위반 방지).
     */

    // 특정 권한 출처에서 이미 캐시된 문서 ID 목록 조회 (배치 INSERT 시 중복 방지용)
    @Query("""
            SELECT c.document.id FROM UserDocumentAccessCache c
            WHERE c.user.id = :userId AND c.sourceType = :sourceType AND c.sourceId = :sourceId
            """)
    List<Long> findDocumentIdsByUserIdAndSourceTypeAndSourceId(
            @Param("userId") Long userId,
            @Param("sourceType") AccessSourceType sourceType,
            @Param("sourceId") Long sourceId);

    /**
     * 그룹 4 — 원장과 교차 검증한 유효성 존재 체크
     *
     * <p>PermissionQueryService의 "3단계: USER 캐시" 판단에서 호출된다.
     */

    // 캐시는 후보이며, 출처 원장의 현재 대상·권한·만료·컬렉션 연결까지 확인해야 최종 허용한다.
    default boolean existsValidReadCache(Long userId, Long documentId) {
        return existsAuthoritativeCache(userId, documentId, "READ");
    }

    default boolean existsValidWriteCache(Long userId, Long documentId) {
        return existsAuthoritativeCache(userId, documentId, "WRITE");
    }

    default boolean existsValidAdminCache(Long userId, Long documentId) {
        return existsAuthoritativeCache(userId, documentId, "ADMIN");
    }

    /** 현재 원장이 증명하는 USER 캐시만 허용하며 오래된 출처·대상·접근 비트는 거부한다. */
    @Query(value = """
        SELECT EXISTS (
          SELECT 1 FROM user_document_access_cache c
          JOIN documents d ON d.id=c.document_id
          WHERE c.user_id=:userId AND c.document_id=:documentId
            AND c.invalidated_at IS NULL AND (c.expires_at IS NULL OR c.expires_at>CURRENT_TIMESTAMP)
            AND ((:capability='READ' AND c.can_read) OR (:capability='WRITE' AND c.can_write)
                 OR (:capability='ADMIN' AND c.can_admin))
            AND (
              (c.source_type='OWNER' AND d.owner_user_id=:userId)
              OR (c.source_type='DIRECT_DOCUMENT_PERMISSION' AND EXISTS (
                SELECT 1 FROM document_permissions p WHERE p.id=c.source_id
                  AND p.document_id=c.document_id AND p.target_type='USER' AND p.user_id=c.user_id
                  AND (p.expires_at IS NULL OR p.expires_at>CURRENT_TIMESTAMP)
                  AND ((:capability='READ' AND p.can_read) OR (:capability='WRITE' AND p.can_write)
                       OR (:capability='ADMIN' AND p.can_admin))))
              OR (c.source_type='DIRECT_COLLECTION_PERMISSION' AND EXISTS (
                SELECT 1 FROM collection_permissions p
                JOIN collections k ON k.id=p.collection_id AND k.status='ACTIVE'
                JOIN collection_documents cd ON cd.collection_id=p.collection_id AND cd.document_id=c.document_id
                WHERE p.id=c.source_id AND p.target_type='USER' AND p.user_id=c.user_id
                  AND (p.expires_at IS NULL OR p.expires_at>CURRENT_TIMESTAMP)
                  AND ((:capability='READ' AND p.can_read) OR (:capability='WRITE' AND p.can_write)
                       OR (:capability='ADMIN' AND p.can_admin))))
            )
        )
        """, nativeQuery = true)
    boolean existsAuthoritativeCache(@Param("userId") Long userId, @Param("documentId") Long documentId,
        @Param("capability") String capability);
}
