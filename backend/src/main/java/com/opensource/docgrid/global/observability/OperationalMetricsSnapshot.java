package com.opensource.docgrid.global.observability;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 한 시점에 읽은 비동기 Queue와 Worker의 운영 상태를 불변 값으로 보관한다.
 *
 * <p>DB 집계 결과와 Prometheus scrape 사이의 경계다. 오래된 항목이 없으면 해당 시각은 {@code null}이며,
 * 모든 개수는 음수가 될 수 없다.
 */
public record OperationalMetricsSnapshot(
    long embeddingClaimableJobs,
    long embeddingProcessingJobs,
    LocalDateTime embeddingOldestClaimableAt,
    long embeddingActiveWorkers,
    long ragProcessingJobs,
    LocalDateTime ragOldestProcessingAt,
    long syncClaimableEvents,
    long syncProcessingEvents,
    LocalDateTime syncOldestClaimableAt
) {

    /** 집계 Query의 잘못된 결과가 Gauge에 게시되지 않도록 불변 조건을 확인한다. */
    public OperationalMetricsSnapshot {
        if (embeddingClaimableJobs < 0 || embeddingProcessingJobs < 0 || embeddingActiveWorkers < 0
            || ragProcessingJobs < 0 || syncClaimableEvents < 0 || syncProcessingEvents < 0) {
            throw new IllegalArgumentException("운영 상태 개수는 음수일 수 없습니다.");
        }
        requireTimestampWhenPresent(embeddingOldestClaimableAt, embeddingClaimableJobs, "Embedding");
        requireTimestampWhenPresent(ragOldestProcessingAt, ragProcessingJobs, "RAG");
        requireTimestampWhenPresent(syncOldestClaimableAt, syncClaimableEvents, "Sync Outbox");
    }

    private static void requireTimestampWhenPresent(LocalDateTime oldestAt, long count, String name) {
        if ((count == 0) != Objects.isNull(oldestAt)) {
            throw new IllegalArgumentException(name + " 개수와 가장 오래된 시각이 일치하지 않습니다.");
        }
    }
}
