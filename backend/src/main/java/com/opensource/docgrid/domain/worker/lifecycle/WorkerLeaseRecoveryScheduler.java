package com.opensource.docgrid.domain.worker.lifecycle;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService.RecoveryResult;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingJobRecoveryQueryService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Heartbeat가 만료된 Worker를 DEAD로 확정하고 만료 Embedding Job Lease를 주기적으로 복구하는 Scheduler.
 *
 * <p>후보 ID 조회는 잠금 없는 Snapshot으로 수행하고 각 후보는 독립 Transaction에 위임한다. DEAD 확정,
 * 후보 조회 또는 한 후보 복구의 실패가 다른 안전한 복구 실행을 Rollback하거나 Scheduler 자체를 중단하지
 * 않도록 단계별로 예외 경계를 둔다. 시험 문서 버전 범위가 설정되면 다른 Worker의 DEAD 상태 변경과 다른
 * 문서의 Lease 복구를 건너뛴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerLeaseRecoveryScheduler {

    private final WorkerNodeCommandService workerNodeCommandService;
    private final EmbeddingJobRecoveryQueryService recoveryQueryService;
    private final EmbeddingJobLeaseRecoveryService leaseRecoveryService;
    private final IndexingWorkerProperties workerProperties;
    private final Clock clock;

    /**
     * DEAD Worker 확정 뒤 만료 Job을 Batch 조회하고 후보별 독립 Transaction으로 복구한다.
     */
    @Scheduled(
        fixedDelayString = "${indexing.worker.lease-recovery-interval:30s}",
        initialDelayString = "${indexing.worker.lease-recovery-interval:30s}"
    )
    public void recoverExpiredLeases() {
        // 1. 시험 범위가 지정된 경우 다른 Worker의 전역 DEAD 상태를 변경하지 않는다.
        int deadWorkerCount = markDeadWorkers();

        // 2. DB TIMESTAMP 정밀도와 맞춘 한 기준 시각으로 후보 Snapshot과 후보별 만료 재검증을 수행한다.
        LocalDateTime recoveredAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<Long> candidateJobIds = findCandidateJobIds(recoveredAt);
        if (candidateJobIds == null) {
            return;
        }

        // 3. 후보마다 독립 Transaction을 호출해 한 건의 불변식 오류나 DB 실패가 다음 후보를 막지 않게 한다.
        int recoveredCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        for (Long jobId : candidateJobIds) {
            try {
                RecoveryResult result = leaseRecoveryService.recover(jobId, recoveredAt);
                if (result.recovered()) {
                    recoveredCount++;
                } else {
                    skippedCount++;
                }
            } catch (RuntimeException exception) {
                failedCount++;
                log.error(
                    "만료 Embedding Job Lease를 복구하지 못했습니다. jobId={}, cause={}",
                    jobId,
                    resolveFailureCategory(exception)
                );
            }
        }

        // 4. 민감한 실행 Context 없이 이번 주기의 DEAD·후보·복구 결과만 운영 진단 정보로 남긴다.
        log.info(
            "Worker Lease 복구 주기를 완료했습니다. deadWorkers={}, candidates={}, recovered={}, skipped={}, failed={}",
            deadWorkerCount,
            candidateJobIds.size(),
            recoveredCount,
            skippedCount,
            failedCount
        );
    }

    /**
     * Heartbeat 만료 Worker의 일괄 상태 전이를 시도하고 실패 시 이번 주기의 Job 복구는 계속 허용한다.
     */
    private int markDeadWorkers() {
        if (workerProperties.getDocumentVersionIdFilter() != null) {
            return 0;
        }
        try {
            return workerNodeCommandService.markDeadWorkers(workerProperties.getDeadThreshold());
        } catch (RuntimeException exception) {
            log.error(
                "Heartbeat 만료 Worker를 DEAD로 확정하지 못했습니다. cause={}",
                resolveFailureCategory(exception)
            );
            return 0;
        }
    }

    /**
     * 한 주기에서 처리할 만료 Job 식별자 Snapshot을 설정된 Batch 크기만큼 조회한다.
     *
     * @return 후보 식별자 목록, 조회 자체가 실패하면 주기 중단을 뜻하는 {@code null}
     */
    private List<Long> findCandidateJobIds(LocalDateTime recoveredAt) {
        try {
            Long documentVersionIdFilter = workerProperties.getDocumentVersionIdFilter();
            if (documentVersionIdFilter != null) {
                return recoveryQueryService.findExpiredJobIdsForDocumentVersion(
                    recoveredAt,
                    workerProperties.getLeaseRecoveryBatchSize(),
                    documentVersionIdFilter
                );
            }
            return recoveryQueryService.findExpiredJobIds(recoveredAt, workerProperties.getLeaseRecoveryBatchSize());
        } catch (RuntimeException exception) {
            log.error(
                "만료 Embedding Job Lease 후보를 조회하지 못했습니다. cause={}",
                resolveFailureCategory(exception)
            );
            return null;
        }
    }

    /**
     * 운영 로그에 예외 메시지 대신 애플리케이션 오류 코드 또는 예외 유형만 남긴다.
     */
    private String resolveFailureCategory(RuntimeException exception) {
        if (exception instanceof DocGridException docGridException) {
            return docGridException.getErrorCode().getCode();
        }
        return exception.getClass().getSimpleName();
    }
}
