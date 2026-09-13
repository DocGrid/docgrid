package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService.RecoveryResult;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.EmbeddingJobAttemptMetricEvent;

/**
 * EmbeddingJobLeaseRecoveryService의 후보 재검증, 선택적 Attempt와 공통 실패 전이 위임을 검증한다.
 *
 * <p>Repository는 이미 잠긴 후보를 반환한다고 가정하고, 회수 Event가 Token을 노출하지 않으며 Skip과
 * 불변식 오류가 후속 상태 변경을 실행하지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobLeaseRecoveryService 테스트")
class EmbeddingJobLeaseRecoveryServiceTest {

    private static final Long JOB_ID = 10L;
    private static final Long WORKER_ID = 1L;
    private static final Long VERSION_ID = 5L;
    private static final Long ATTEMPT_ID = 100L;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final LocalDateTime RECOVERED_AT = LocalDateTime.of(2026, 8, 3, 15, 0);
    private static final LocalDateTime EXPIRED_AT = RECOVERED_AT.minusSeconds(1);

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private IndexingFailureTransitionService failureTransitionService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private EmbeddingJobLeaseRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new EmbeddingJobLeaseRecoveryService(
            embeddingJobRepository,
            embeddingJobAttemptRepository,
            indexingEventRepository,
            failureTransitionService,
            applicationEventPublisher
        );
        // 공통 실패 전이는 mock이므로, 운영 구현처럼 회수 후 상태가 PENDING이 되도록 반영한다.
        lenient().doAnswer(invocation -> {
            ReflectionTestUtils.setField(
                invocation.<EmbeddingJob>getArgument(0),
                "status",
                EmbeddingJobStatus.PENDING
            );
            return null;
        }).when(failureTransitionService).transition(
            any(), any(), any(), any(), anyBoolean(), any(), any()
        );
    }

    @Test
    @DisplayName("만료 Job의 STARTED Attempt를 Lease 만료 실패 전이에 함께 전달한다")
    void recover_transitionsStartedAttemptAndSavesLeaseExpiredEvent() {
        EmbeddingJob embeddingJob = createExpiredJob();
        EmbeddingJobAttempt attempt = createAttempt(embeddingJob, AttemptStatus.STARTED);
        given(embeddingJobRepository.findExpiredByIdForUpdateSkipLocked(JOB_ID, RECOVERED_AT))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));

        RecoveryResult result = recoveryService.recover(JOB_ID, RECOVERED_AT);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        IndexingEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(IndexingEventType.LEASE_EXPIRED);
        assertThat(event.getOccurredAt()).isEqualTo(RECOVERED_AT);
        assertThat(event.getMetadataJson())
            .contains("\"workerId\":1", "\"attemptId\":100", "\"attemptNo\":1")
            .contains("\"expiredAt\":\"" + EXPIRED_AT + "\"")
            .doesNotContain(CLAIM_TOKEN);
        then(failureTransitionService).should().transition(
            embeddingJob,
            Optional.of(attempt),
            "WORKER_LEASE_EXPIRED",
            "Embedding Job Lease가 만료되어 현재 실행을 회수했습니다.",
            true,
            RECOVERED_AT,
            Duration.ZERO
        );
        assertThat(result.recovered()).isTrue();
        assertThat(result.jobId()).isEqualTo(JOB_ID);
        then(applicationEventPublisher).should()
            .publishEvent(EmbeddingJobAttemptMetricEvent.leaseExpired(EmbeddingJobStatus.PENDING));
    }

    @Test
    @DisplayName("Attempt 시작 전 만료된 Claim은 가짜 Attempt 없이 Job만 복구한다")
    void recover_transitionsJobWithoutCreatingAttempt_when_attemptDoesNotExist() {
        EmbeddingJob embeddingJob = createExpiredJob();
        given(embeddingJobRepository.findExpiredByIdForUpdateSkipLocked(JOB_ID, RECOVERED_AT))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.empty());

        RecoveryResult result = recoveryService.recover(JOB_ID, RECOVERED_AT);

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getMetadataJson())
            .contains("\"workerId\":1", "\"retryCountBefore\":0")
            .doesNotContain("attemptId", "attemptNo", CLAIM_TOKEN);
        then(failureTransitionService).should().transition(
            embeddingJob,
            Optional.empty(),
            "WORKER_LEASE_EXPIRED",
            "Embedding Job Lease가 만료되어 현재 실행을 회수했습니다.",
            true,
            RECOVERED_AT,
            Duration.ZERO
        );
        assertThat(result.recovered()).isTrue();
        then(applicationEventPublisher).should()
            .publishEvent(EmbeddingJobAttemptMetricEvent.leaseExpired(EmbeddingJobStatus.PENDING));
    }

    @Test
    @DisplayName("후보가 이미 갱신·완료됐거나 다른 Scheduler가 잠갔으면 상태 변경 없이 Skip한다")
    void recover_skips_when_candidateCannotBeLocked() {
        given(embeddingJobRepository.findExpiredByIdForUpdateSkipLocked(JOB_ID, RECOVERED_AT))
            .willReturn(Optional.empty());

        RecoveryResult result = recoveryService.recover(JOB_ID, RECOVERED_AT);

        assertThat(result.recovered()).isFalse();
        assertThat(result.status()).isNull();
        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
        then(indexingEventRepository).shouldHaveNoInteractions();
        then(failureTransitionService).shouldHaveNoInteractions();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("현재 Claim의 Attempt가 이미 종료됐다면 불변식 오류로 전체 복구를 중단한다")
    void recover_throws_when_currentAttemptIsAlreadyTerminal() {
        EmbeddingJob embeddingJob = createExpiredJob();
        EmbeddingJobAttempt attempt = createAttempt(embeddingJob, AttemptStatus.FAILED);
        given(embeddingJobRepository.findExpiredByIdForUpdateSkipLocked(JOB_ID, RECOVERED_AT))
            .willReturn(Optional.of(embeddingJob));
        given(embeddingJobAttemptRepository.findByEmbeddingJobIdAndClaimToken(JOB_ID, CLAIM_TOKEN))
            .willReturn(Optional.of(attempt));

        assertThatThrownBy(() -> recoveryService.recover(JOB_ID, RECOVERED_AT))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
        then(indexingEventRepository).should(never()).save(any());
        then(failureTransitionService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("비표준 Claim Token을 가진 만료 Job은 데이터 불변식 오류로 거부한다")
    void recover_throws_when_claimTokenIsNotCanonicalUuid() {
        EmbeddingJob embeddingJob = createExpiredJob();
        ReflectionTestUtils.setField(embeddingJob, "claimToken", "not-a-canonical-uuid");
        given(embeddingJobRepository.findExpiredByIdForUpdateSkipLocked(JOB_ID, RECOVERED_AT))
            .willReturn(Optional.of(embeddingJob));

        assertThatThrownBy(() -> recoveryService.recover(JOB_ID, RECOVERED_AT))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.DOCUMENT_INDEXING_FAILURE_INCONSISTENT
            );
        then(embeddingJobAttemptRepository).shouldHaveNoInteractions();
        then(failureTransitionService).shouldHaveNoInteractions();
    }

    private EmbeddingJob createExpiredJob() {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName("recovery-worker")
            .instanceId("recovery-worker-instance")
            .status(WorkerStatus.ACTIVE)
            .lastHeartbeatAt(RECOVERED_AT.minusMinutes(1))
            .startedAt(RECOVERED_AT.minusMinutes(10))
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WORKER_ID);
        DocumentVersion documentVersion = DocumentVersion.builder()
            .versionNo(1)
            .status(DocumentVersionStatus.PARSING)
            .build();
        ReflectionTestUtils.setField(documentVersion, "id", VERSION_ID);
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .documentVersion(documentVersion)
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        embeddingJob.claim(
            workerNode,
            CLAIM_TOKEN,
            RECOVERED_AT.minusMinutes(5),
            EXPIRED_AT
        );
        return embeddingJob;
    }

    private EmbeddingJobAttempt createAttempt(EmbeddingJob embeddingJob, AttemptStatus status) {
        EmbeddingJobAttempt attempt = EmbeddingJobAttempt.builder()
            .embeddingJob(embeddingJob)
            .workerNode(embeddingJob.getLockedByWorker())
            .attemptNo(1)
            .claimToken(CLAIM_TOKEN)
            .status(status)
            .startedAt(RECOVERED_AT.minusMinutes(4))
            .build();
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
        return attempt;
    }
}
