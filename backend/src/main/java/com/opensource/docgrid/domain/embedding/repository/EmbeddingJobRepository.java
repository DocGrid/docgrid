package com.opensource.docgrid.domain.embedding.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

/**
 * Embedding Job Queue의 영속성과 Claim 후보 행 잠금을 담당하는 Repository.
 *
 * <p>일반 CRUD 외에 PostgreSQL의 {@code FOR UPDATE SKIP LOCKED}로 Queue Claim과 만료 Lease 복구 경쟁을
 * 제어하고, 단일 Job의 후속 상태·Attempt 변경에는 표준 JPA 쓰기 행 잠금을 제공한다.
 */
public interface EmbeddingJobRepository extends JpaRepository<EmbeddingJob, Long> {

    /**
     * 버전 타임라인에 필요한 Job Snapshot을 최신 Job부터 Worker와 함께 일괄 조회한다.
     */
    @Query("""
        SELECT job
        FROM EmbeddingJob job
        LEFT JOIN FETCH job.lockedByWorker
        WHERE job.documentVersion.id IN :documentVersionIds
        ORDER BY job.id DESC
        """)
    List<EmbeddingJob> findHistoryJobsByDocumentVersionIds(
        @Param("documentVersionIds") Collection<Long> documentVersionIds
    );

    Optional<EmbeddingJob> findBySourceEventId(UUID sourceEventId);

    Optional<EmbeddingJob> findTopByDocumentVersionIdAndEmbeddingModelIdOrderByIdDesc(
        Long documentVersionId,
        Long embeddingModelId
    );

    boolean existsByDocumentVersionIdAndStatusIn(
        Long documentVersionId,
        Collection<EmbeddingJobStatus> statuses
    );

    /**
     * 관리자 목록 화면에 필요한 연관관계를 함께 조회하면서 선택 필터와 Pagination을 적용한다.
     *
     * <p>정렬은 호출자가 createdAt, id 역순으로 고정한다. 모든 Join은 To-One 관계이므로 Page Content의
     * 행 수를 늘리지 않고 Converter의 Lazy 추가 조회를 방지한다.
     */
    @Query(
        value = """
            SELECT job
            FROM EmbeddingJob job
            JOIN FETCH job.documentVersion version
            JOIN FETCH version.document document
            LEFT JOIN FETCH document.currentVersion currentVersion
            JOIN FETCH job.embeddingModel model
            LEFT JOIN FETCH job.lockedByWorker worker
            WHERE (:status IS NULL OR job.status = :status)
              AND (:documentId IS NULL OR document.id = :documentId)
              AND (:workerId IS NULL OR worker.id = :workerId)
            """,
        countQuery = """
            SELECT COUNT(job)
            FROM EmbeddingJob job
            JOIN job.documentVersion version
            JOIN version.document document
            LEFT JOIN job.lockedByWorker worker
            WHERE (:status IS NULL OR job.status = :status)
              AND (:documentId IS NULL OR document.id = :documentId)
              AND (:workerId IS NULL OR worker.id = :workerId)
            """
    )
    Page<EmbeddingJob> findAdminJobs(
        @Param("status") EmbeddingJobStatus status,
        @Param("documentId") Long documentId,
        @Param("workerId") Long workerId,
        Pageable pageable
    );

    /**
     * 관리자 상세 응답에 필요한 Job과 To-One 연관관계를 한 Query로 조회한다.
     */
    @Query("""
        SELECT job
        FROM EmbeddingJob job
        JOIN FETCH job.documentVersion version
        JOIN FETCH version.document document
        LEFT JOIN FETCH document.currentVersion currentVersion
        JOIN FETCH job.embeddingModel model
        LEFT JOIN FETCH job.lockedByWorker worker
        WHERE job.id = :jobId
        """)
    Optional<EmbeddingJob> findAdminDetailById(@Param("jobId") Long jobId);

    /**
     * 같은 Version에 동시에 살아 있는 Job이 하나뿐인지 완료 직전에 확인한다.
     */
    long countByDocumentVersionIdAndStatusIn(
        Long documentVersionId,
        Collection<EmbeddingJobStatus> statuses
    );

    /**
     * 지정된 Version 중 활성 상태 Job이 존재하는 Version ID를 일괄 조회한다.
     */
    @Query("""
        SELECT DISTINCT job.documentVersion.id
        FROM EmbeddingJob job
        WHERE job.documentVersion.id IN :documentVersionIds
          AND job.status IN :statuses
        """)
    List<Long> findDocumentVersionIdsWithStatusIn(
        @Param("documentVersionIds") Collection<Long> documentVersionIds,
        @Param("statuses") Collection<EmbeddingJobStatus> statuses
    );

    /**
     * 우선순위 Queue 정책에 따라 현재 실행 가능한 다음 PENDING Job 한 건을 잠금 상태로 조회한다.
     *
     * <p>다른 Transaction이 잠근 행은 기다리지 않고 건너뛴다. 반환된 행 잠금은 호출한 Service의
     * Transaction이 끝날 때까지 유지돼야 하므로 반드시 Transaction 내부에서 호출한다.
     *
     * @param claimedAt Retry 예약 시각과 비교할 Claim 기준 시각
     * @return 잠금을 획득한 다음 PENDING Job, 처리 가능한 후보가 없으면 빈 값
     */
    @Query(value = """
        SELECT job.*
        FROM embedding_jobs job
        WHERE job.status = 'PENDING'
          AND (job.next_retry_at IS NULL OR job.next_retry_at <= :claimedAt)
        ORDER BY job.priority DESC,
                 job.created_at ASC,
                 job.id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<EmbeddingJob> findNextPendingForUpdate(@Param("claimedAt") LocalDateTime claimedAt);

    /**
     * 시험 프로필에서 지정한 문서 버전의 PENDING Job만 잠그고, 다른 버전의 Queue는 건드리지 않는다.
     */
    @Query(value = """
        SELECT job.*
        FROM embedding_jobs job
        WHERE job.status = 'PENDING'
          AND job.document_version_id = :documentVersionId
          AND (job.next_retry_at IS NULL OR job.next_retry_at <= :claimedAt)
        ORDER BY job.priority DESC,
                 job.created_at ASC,
                 job.id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<EmbeddingJob> findNextPendingForDocumentVersionForUpdate(
        @Param("claimedAt") LocalDateTime claimedAt,
        @Param("documentVersionId") Long documentVersionId
    );

    /**
     * 만료 Lease 복구 대상인 PROCESSING Job ID를 오래 만료된 순서대로 제한 조회한다.
     *
     * <p>이 결과는 작업 분배용 Snapshot일 뿐 정확성 경계가 아니다. 각 후보는 복구 Transaction에서 다시
     * 잠그고 만료 여부를 검증해야 한다.
     */
    @Query(value = """
        SELECT job.id
        FROM embedding_jobs job
        WHERE job.status = 'PROCESSING'
          AND job.lock_expires_at IS NOT NULL
          AND job.lock_expires_at <= :recoveredAt
        ORDER BY job.lock_expires_at ASC,
                 job.id ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<Long> findExpiredProcessingJobIds(
        @Param("recoveredAt") LocalDateTime recoveredAt,
        @Param("batchSize") int batchSize
    );

    /**
     * 시험 문서 버전에 속하는 만료 Lease만 복구 후보로 제공한다.
     */
    @Query(value = """
        SELECT job.id
        FROM embedding_jobs job
        WHERE job.status = 'PROCESSING'
          AND job.document_version_id = :documentVersionId
          AND job.lock_expires_at IS NOT NULL
          AND job.lock_expires_at <= :recoveredAt
        ORDER BY job.lock_expires_at ASC,
                 job.id ASC
        LIMIT :batchSize
        """, nativeQuery = true)
    List<Long> findExpiredProcessingJobIdsForDocumentVersion(
        @Param("recoveredAt") LocalDateTime recoveredAt,
        @Param("batchSize") int batchSize,
        @Param("documentVersionId") Long documentVersionId
    );

    /**
     * 지정한 Job이 아직 만료 PROCESSING 상태일 때만 쓰기 잠금을 획득한다.
     *
     * <p>다른 복구 Transaction이 선점한 행은 기다리지 않고 건너뛴다. 반환된 행은 호출 Transaction이
     * 끝날 때까지 잠기므로 반드시 독립 Transaction 안에서 호출한다.
     */
    @Query(value = """
        SELECT job.*
        FROM embedding_jobs job
        WHERE job.id = :jobId
          AND job.status = 'PROCESSING'
          AND job.lock_expires_at IS NOT NULL
          AND job.lock_expires_at <= :recoveredAt
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<EmbeddingJob> findExpiredByIdForUpdateSkipLocked(
        @Param("jobId") Long jobId,
        @Param("recoveredAt") LocalDateTime recoveredAt
    );

    /**
     * 지정한 Job을 현재 Transaction이 끝날 때까지 쓰기 잠금 상태로 조회한다.
     *
     * <p>Attempt 생성·완료·복구 기능은 모두 Job을 먼저 잠그는 순서를 유지해야 한다. 그래야 Claim
     * 소유권 교체와 Job별 Attempt 번호 할당이 서로 겹치지 않는다.
     *
     * @param jobId 잠글 Embedding Job 식별자
     * @return 잠금을 획득한 Job, 존재하지 않으면 빈 값
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM EmbeddingJob job WHERE job.id = :jobId")
    Optional<EmbeddingJob> findByIdForUpdate(@Param("jobId") Long jobId);

    /**
     * 대시보드 집계 카드(대기/처리 중/실패 작업 수)에 사용하는 상태별 Job 수를 센다.
     */
    long countByStatus(EmbeddingJobStatus status);

    /**
     * 관리자 전체 재처리 후보 ID를 오래된 순서로 조회한다.
     *
     * <p>실제 가능 여부는 각 Job을 잠근 Command에서 다시 판정하므로 여기서는 상태 Snapshot만 사용한다.
     */
    @Query("""
        SELECT job.id
        FROM EmbeddingJob job
        WHERE job.status = :status
        ORDER BY job.id ASC
        """)
    List<Long> findIdsByStatusOrderByIdAsc(@Param("status") EmbeddingJobStatus status);

    /**
     * 완료된 Job의 평균 처리 시간을 밀리초 단위로 계산한다.
     *
     * <p>Queue 대기 시간({@code created_at})은 제외하고 Worker가 실제로 처리한 구간({@code started_at}
     * ~ {@code completed_at})만 반영한다. 완료된 Job이 없으면 {@code null}을 반환한다.
     */
    @Query(
        value = """
            SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)
            FROM embedding_jobs
            WHERE status = 'INDEXED'
              AND started_at IS NOT NULL
              AND completed_at IS NOT NULL
            """,
        nativeQuery = true
    )
    Double findAverageProcessingMillis();
}
