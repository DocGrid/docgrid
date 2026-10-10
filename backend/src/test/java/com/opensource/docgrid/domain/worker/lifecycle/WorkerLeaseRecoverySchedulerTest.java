package com.opensource.docgrid.domain.worker.lifecycle;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService.RecoveryResult;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingJobRecoveryQueryService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

/**
 * WorkerLeaseRecoveryScheduler의 단계 순서와 DEAD·후보·개별 Job 실패 격리를 검증한다.
 *
 * <p>Scheduling Thread와 실제 Transaction은 사용하지 않고 한 주기 메서드를 직접 호출해 안전하게 처리할
 * 수 있는 다음 단계가 선행 단계의 부분 실패 뒤에도 계속 실행되는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerLeaseRecoveryScheduler 테스트")
class WorkerLeaseRecoverySchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 15, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-03T06:00:00Z"),
        ZoneId.of("Asia/Seoul")
    );

    @Mock private WorkerNodeCommandService workerNodeCommandService;
    @Mock private EmbeddingJobRecoveryQueryService recoveryQueryService;
    @Mock private EmbeddingJobLeaseRecoveryService leaseRecoveryService;

    private IndexingWorkerProperties workerProperties;
    private WorkerLeaseRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        workerProperties = new IndexingWorkerProperties();
        workerProperties.setDeadThreshold(Duration.ofSeconds(30));
        workerProperties.setLeaseRecoveryBatchSize(100);
        scheduler = new WorkerLeaseRecoveryScheduler(
            workerNodeCommandService,
            recoveryQueryService,
            leaseRecoveryService,
            workerProperties,
            FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("DEAD Worker를 확정한 뒤 후보를 조회하고 각 Job을 복구한다")
    void recoverExpiredLeases_processesCandidateBatch() {
        given(workerNodeCommandService.markDeadWorkers(Duration.ofSeconds(30))).willReturn(2);
        given(recoveryQueryService.findExpiredJobIds(NOW, 100)).willReturn(List.of(10L, 11L));
        given(leaseRecoveryService.recover(10L, NOW))
            .willReturn(new RecoveryResult(10L, true, EmbeddingJobStatus.PENDING));
        given(leaseRecoveryService.recover(11L, NOW))
            .willReturn(new RecoveryResult(11L, false, null));

        scheduler.recoverExpiredLeases();

        then(workerNodeCommandService).should().markDeadWorkers(Duration.ofSeconds(30));
        then(recoveryQueryService).should().findExpiredJobIds(NOW, 100);
        then(leaseRecoveryService).should().recover(10L, NOW);
        then(leaseRecoveryService).should().recover(11L, NOW);
    }

    @Test
    @DisplayName("문서 버전 범위를 지정하면 다른 Worker의 DEAD 상태와 다른 Job을 건드리지 않는다")
    void recoverExpiredLeases_scopesCandidateAndSkipsGlobalDeadUpdate() {
        workerProperties.setDocumentVersionIdFilter(42L);
        given(recoveryQueryService.findExpiredJobIdsForDocumentVersion(NOW, 100, 42L))
            .willReturn(List.of(10L));
        given(leaseRecoveryService.recover(10L, NOW))
            .willReturn(new RecoveryResult(10L, true, EmbeddingJobStatus.PENDING));

        scheduler.recoverExpiredLeases();

        then(workerNodeCommandService).shouldHaveNoInteractions();
        then(recoveryQueryService).should().findExpiredJobIdsForDocumentVersion(NOW, 100, 42L);
        then(recoveryQueryService).should(never()).findExpiredJobIds(NOW, 100);
        then(leaseRecoveryService).should().recover(10L, NOW);
    }

    @Test
    @DisplayName("DEAD Worker 확정이 실패해도 Lease 만료 후보 복구를 계속한다")
    void recoverExpiredLeases_continues_when_deadWorkerUpdateFails() {
        given(workerNodeCommandService.markDeadWorkers(Duration.ofSeconds(30)))
            .willThrow(new IllegalStateException("dead update failed"));
        given(recoveryQueryService.findExpiredJobIds(NOW, 100)).willReturn(List.of(10L));
        given(leaseRecoveryService.recover(10L, NOW))
            .willReturn(new RecoveryResult(10L, true, EmbeddingJobStatus.PENDING));

        scheduler.recoverExpiredLeases();

        then(recoveryQueryService).should().findExpiredJobIds(NOW, 100);
        then(leaseRecoveryService).should().recover(10L, NOW);
    }

    @Test
    @DisplayName("후보 조회가 실패하면 후보별 복구를 실행하지 않고 이번 주기만 종료한다")
    void recoverExpiredLeases_stopsCurrentCycle_when_candidateQueryFails() {
        given(recoveryQueryService.findExpiredJobIds(NOW, 100))
            .willThrow(new IllegalStateException("candidate query failed"));

        scheduler.recoverExpiredLeases();

        then(leaseRecoveryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("한 Job 복구가 실패해도 Batch의 다음 후보를 계속 처리한다")
    void recoverExpiredLeases_continues_when_oneCandidateFails() {
        given(recoveryQueryService.findExpiredJobIds(NOW, 100)).willReturn(List.of(10L, 11L));
        given(leaseRecoveryService.recover(10L, NOW))
            .willThrow(new IllegalStateException("single recovery failed"));
        given(leaseRecoveryService.recover(11L, NOW))
            .willReturn(new RecoveryResult(11L, true, EmbeddingJobStatus.PENDING));

        scheduler.recoverExpiredLeases();

        then(leaseRecoveryService).should().recover(10L, NOW);
        then(leaseRecoveryService).should().recover(11L, NOW);
    }
}
