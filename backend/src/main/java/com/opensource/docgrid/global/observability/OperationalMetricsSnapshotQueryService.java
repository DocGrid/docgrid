package com.opensource.docgrid.global.observability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 비동기 Pipeline의 현재 운영 상태를 행 로딩 없이 목적별 aggregate query로 읽는다.
 *
 * <p>호출자는 이 짧은 읽기 Transaction의 결과만 메모리에 보관한다. Prometheus scrape 경로에서는 이
 * 서비스를 호출하지 않아, 감시 트래픽이 DB 부하로 이어지지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 2)
public class OperationalMetricsSnapshotQueryService {

    private static final String EMBEDDING_SNAPSHOT_SQL = """
        SELECT
            (
                SELECT COUNT(*)
                FROM embedding_jobs job
                WHERE job.status = 'PENDING'
                  AND (job.next_retry_at IS NULL OR job.next_retry_at <= ?)
            ) AS claimable_jobs,
            (
                SELECT COUNT(*)
                FROM embedding_jobs job
                WHERE job.status = 'PROCESSING'
            ) AS processing_jobs,
            (
                SELECT MIN(COALESCE(job.next_retry_at, job.created_at))
                FROM embedding_jobs job
                WHERE job.status = 'PENDING'
                  AND (job.next_retry_at IS NULL OR job.next_retry_at <= ?)
            ) AS oldest_claimable_at,
            (
                SELECT COUNT(*)
                FROM worker_nodes worker
                WHERE worker.status IN ('ACTIVE', 'IDLE')
                  AND worker.last_heartbeat_at > ?
            ) AS active_workers
        """;

    private static final String RAG_SNAPSHOT_SQL = """
        SELECT
            (
                SELECT COUNT(*)
                FROM rag_responses response
                WHERE response.status = 'PROCESSING'
            ) AS processing_jobs,
            (
                SELECT MIN(response.created_at)
                FROM rag_responses response
                WHERE response.status = 'PROCESSING'
            ) AS oldest_processing_at
        """;

    private static final String SYNC_SNAPSHOT_SQL = """
        SELECT
            (
                SELECT COUNT(*)
                FROM sync_outbox_events event
                WHERE event.status = 'PENDING'
                  AND event.available_at <= ?
            ) AS claimable_events,
            (
                SELECT COUNT(*)
                FROM sync_outbox_events event
                WHERE event.status = 'PROCESSING'
            ) AS processing_events,
            (
                SELECT MIN(event.available_at)
                FROM sync_outbox_events event
                WHERE event.status = 'PENDING'
                  AND event.available_at <= ?
            ) AS oldest_claimable_at
        """;

    private final JdbcTemplate jdbcTemplate;

    @Value("${indexing.worker.dead-threshold:30s}")
    private Duration workerDeadThreshold;

    /**
     * 동일한 관측 시각을 세 Queue 집계에 적용해 경계 시각의 포함 여부가 서로 어긋나지 않게 한다.
     */
    public OperationalMetricsSnapshot load(LocalDateTime observedAt) {
        // 1. Worker의 실효 상태는 관리자 조회와 같은 Heartbeat 만료 기준으로 계산한다.
        LocalDateTime heartbeatDeadline = observedAt.minus(workerDeadThreshold);

        // 2. Queue별 집계는 각각 한 SQL로 끝내 Entity 수에 비례하는 조회를 만들지 않는다.
        EmbeddingSnapshot embedding = jdbcTemplate.queryForObject(
            EMBEDDING_SNAPSHOT_SQL,
            this::mapEmbeddingSnapshot,
            observedAt,
            observedAt,
            heartbeatDeadline
        );
        RagSnapshot rag = jdbcTemplate.queryForObject(RAG_SNAPSHOT_SQL, this::mapRagSnapshot);
        SyncSnapshot sync = jdbcTemplate.queryForObject(
            SYNC_SNAPSHOT_SQL,
            this::mapSyncSnapshot,
            observedAt,
            observedAt
        );

        // 3. 세 Query가 모두 성공한 경우에만 하나의 원자적 Snapshot 후보를 반환한다.
        return new OperationalMetricsSnapshot(
            embedding.claimableJobs(),
            embedding.processingJobs(),
            embedding.oldestClaimableAt(),
            embedding.activeWorkers(),
            rag.processingJobs(),
            rag.oldestProcessingAt(),
            sync.claimableEvents(),
            sync.processingEvents(),
            sync.oldestClaimableAt()
        );
    }

    private EmbeddingSnapshot mapEmbeddingSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EmbeddingSnapshot(
            resultSet.getLong("claimable_jobs"),
            resultSet.getLong("processing_jobs"),
            resultSet.getObject("oldest_claimable_at", LocalDateTime.class),
            resultSet.getLong("active_workers")
        );
    }

    private RagSnapshot mapRagSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RagSnapshot(
            resultSet.getLong("processing_jobs"),
            resultSet.getObject("oldest_processing_at", LocalDateTime.class)
        );
    }

    private SyncSnapshot mapSyncSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SyncSnapshot(
            resultSet.getLong("claimable_events"),
            resultSet.getLong("processing_events"),
            resultSet.getObject("oldest_claimable_at", LocalDateTime.class)
        );
    }

    /** Embedding Queue와 유효 Worker를 한 SQL에서 읽은 내부 집계 값이다. */
    private record EmbeddingSnapshot(
        long claimableJobs,
        long processingJobs,
        LocalDateTime oldestClaimableAt,
        long activeWorkers
    ) {
    }

    /** RAG PROCESSING Queue를 한 SQL에서 읽은 내부 집계 값이다. */
    private record RagSnapshot(long processingJobs, LocalDateTime oldestProcessingAt) {
    }

    /** Sync Outbox Queue를 한 SQL에서 읽은 내부 집계 값이다. */
    private record SyncSnapshot(
        long claimableEvents,
        long processingEvents,
        LocalDateTime oldestClaimableAt
    ) {
    }
}
