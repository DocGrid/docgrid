package com.opensource.docgrid.domain.embedding.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.fixture.WorkerNodeFixture;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Embedding Job Claim Command의 Worker 검증, Lease 계산, 상태 전이, 이벤트 저장을 검증하는 단위 테스트.
 *
 * <p>고정 Clock과 Mock Repository를 사용해 Heartbeat 경계와 Claim 시각을 결정적으로 검증하고,
 * 오류 상황에서는 Job 잠금 조회가 실행되지 않는지도 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobClaimService 테스트")
class EmbeddingJobClaimServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Instant NOW_INSTANT = Instant.parse("2026-07-22T06:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 15, 0);
    private static final Long JOB_ID = 10L;

    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private WorkerNodeRepository workerNodeRepository;
    @Mock private IndexingEventRepository indexingEventRepository;
    @Mock private EmbeddingJobConverter embeddingJobConverter;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private EmbeddingJobClaimService embeddingJobClaimService;
    private IndexingWorkerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IndexingWorkerProperties();
        properties.setDeadThreshold(Duration.ofSeconds(30));
        properties.setLeaseDuration(Duration.ofMinutes(5));
        Clock clock = Clock.fixed(NOW_INSTANT, ZONE_ID);
        embeddingJobClaimService = new EmbeddingJobClaimService(
            embeddingJobRepository,
            workerNodeRepository,
            indexingEventRepository,
            embeddingJobConverter,
            properties,
            clock,
            applicationEventPublisher
        );
    }

    @Test
    @DisplayName("ACTIVE Worker가 PENDING Job을 Claim하고 LOCKED 이벤트를 저장한다")
    void claim_claimsPendingJob_when_workerIsActive() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW);
        EmbeddingJob embeddingJob = createPendingJob();
        ClaimedEmbeddingJobResponse expected = new ClaimedEmbeddingJobResponse(
            JOB_ID,
            EmbeddingJobStatus.PROCESSING,
            WorkerNodeFixture.WORKER_ID,
            5L,
            2L,
            "token",
            NOW,
            NOW.plusMinutes(5)
        );
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));
        given(embeddingJobRepository.findNextPendingForUpdate(NOW)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobConverter.toClaimedResponse(embeddingJob)).willReturn(expected);

        Optional<ClaimedEmbeddingJobResponse> result = embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID);

        assertThat(result).contains(expected);
        assertThat(embeddingJob.getStatus()).isEqualTo(EmbeddingJobStatus.PROCESSING);
        assertThat(embeddingJob.getLockedByWorker()).isSameAs(workerNode);
        assertThat(embeddingJob.getLockedAt()).isEqualTo(NOW);
        assertThat(embeddingJob.getLockExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(embeddingJob.getStartedAt()).isEqualTo(NOW);
        assertThatCodeIsUuid(embeddingJob.getClaimToken());

        ArgumentCaptor<IndexingEvent> eventCaptor = ArgumentCaptor.forClass(IndexingEvent.class);
        then(indexingEventRepository).should().save(eventCaptor.capture());
        IndexingEvent event = eventCaptor.getValue();
        assertThat(event.getEmbeddingJob()).isSameAs(embeddingJob);
        assertThat(event.getEventType()).isEqualTo(IndexingEventType.LOCKED);
        assertThat(event.getFromStatus()).isEqualTo("PENDING");
        assertThat(event.getToStatus()).isEqualTo("PROCESSING");
        assertThat(event.getOccurredAt()).isEqualTo(NOW);
        assertThat(event.getMessage()).doesNotContain(embeddingJob.getClaimToken());
    }

    @Test
    @DisplayName("IDLE Worker도 PENDING Job을 Claim할 수 있다")
    void claim_claimsPendingJob_when_workerIsIdle() {
        WorkerNode workerNode = createWorker(WorkerStatus.IDLE, NOW);
        EmbeddingJob embeddingJob = createPendingJob();
        ClaimedEmbeddingJobResponse expected = new ClaimedEmbeddingJobResponse(
            JOB_ID, EmbeddingJobStatus.PROCESSING, WorkerNodeFixture.WORKER_ID,
            5L, 2L, "token", NOW, NOW.plusMinutes(5)
        );
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));
        given(embeddingJobRepository.findNextPendingForUpdate(NOW)).willReturn(Optional.of(embeddingJob));
        given(embeddingJobConverter.toClaimedResponse(embeddingJob)).willReturn(expected);

        assertThat(embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID)).contains(expected);
    }

    @Test
    @DisplayName("문서 버전 범위를 지정하면 그 버전의 Job만 Claim 후보로 조회한다")
    void claim_queriesOnlySelectedDocumentVersion_when_filterIsSet() {
        properties.setDocumentVersionIdFilter(42L);
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW);
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));
        given(embeddingJobRepository.findNextPendingForDocumentVersionForUpdate(NOW, 42L))
            .willReturn(Optional.empty());

        assertThat(embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID)).isEmpty();
        then(embeddingJobRepository).should().findNextPendingForDocumentVersionForUpdate(NOW, 42L);
        then(embeddingJobRepository).should(never()).findNextPendingForUpdate(any());
        then(indexingEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Worker가 없으면 WORKER_NOT_FOUND 예외가 발생한다")
    void claim_throws_when_workerDoesNotExist() {
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WORKER_NOT_FOUND);
        then(embeddingJobRepository).should(never()).findNextPendingForUpdate(any());
    }

    @Test
    @DisplayName("STOPPED Worker는 Job을 Claim할 수 없다")
    void claim_throws_when_workerIsStopped() {
        WorkerNode workerNode = createWorker(WorkerStatus.STOPPED, NOW);
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));

        assertThatThrownBy(() -> embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WORKER_NOT_AVAILABLE);
        then(embeddingJobRepository).should(never()).findNextPendingForUpdate(any());
    }

    @Test
    @DisplayName("Heartbeat가 DEAD 기준 시각과 같으면 Job을 Claim할 수 없다")
    void claim_throws_when_workerHeartbeatIsExpired() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW.minusSeconds(30));
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));

        assertThatThrownBy(() -> embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WORKER_NOT_AVAILABLE);
        then(embeddingJobRepository).should(never()).findNextPendingForUpdate(any());
    }

    @Test
    @DisplayName("Claim할 PENDING Job이 없으면 빈 결과를 반환한다")
    void claim_returnsEmpty_when_pendingJobDoesNotExist() {
        WorkerNode workerNode = createWorker(WorkerStatus.ACTIVE, NOW);
        given(workerNodeRepository.findById(WorkerNodeFixture.WORKER_ID)).willReturn(Optional.of(workerNode));
        given(embeddingJobRepository.findNextPendingForUpdate(NOW)).willReturn(Optional.empty());

        assertThat(embeddingJobClaimService.claim(WorkerNodeFixture.WORKER_ID)).isEmpty();
        then(indexingEventRepository).should(never()).save(any());
        then(embeddingJobConverter).shouldHaveNoInteractions();
    }

    private WorkerNode createWorker(WorkerStatus status, LocalDateTime lastHeartbeatAt) {
        WorkerNode workerNode = WorkerNode.builder()
            .workerName(WorkerNodeFixture.WORKER_NAME)
            .instanceId(WorkerNodeFixture.INSTANCE_ID)
            .hostName(WorkerNodeFixture.HOST_NAME)
            .ipAddress(WorkerNodeFixture.IP_ADDRESS)
            .status(status)
            .lastHeartbeatAt(lastHeartbeatAt)
            .startedAt(WorkerNodeFixture.STARTED_AT)
            .build();
        ReflectionTestUtils.setField(workerNode, "id", WorkerNodeFixture.WORKER_ID);
        return workerNode;
    }

    private EmbeddingJob createPendingJob() {
        EmbeddingJob embeddingJob = EmbeddingJob.builder()
            .status(EmbeddingJobStatus.PENDING)
            .priority(0)
            .maxRetryCount(3)
            .build();
        ReflectionTestUtils.setField(embeddingJob, "id", JOB_ID);
        return embeddingJob;
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }
}
