package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobConverter;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.event.EmbeddingJobStatusChangedEvent;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.entity.WorkerNode;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.enums.WorkerStatus;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.domain.worker.repository.WorkerNodeRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 살아 있는 Worker에게 PENDING Embedding Job 하나의 Lease 소유권을 부여하는 Command Service.
 *
 * <p>Worker 생존 검증, Queue 행 잠금, PROCESSING 상태 전이, Claim Token 발급, LOCKED 이벤트 저장을
 * 하나의 Transaction으로 묶어 부분 성공과 중복 Claim을 방지한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EmbeddingJobClaimService {

    private static final Set<WorkerStatus> CLAIMABLE_WORKER_STATUSES = EnumSet.of(
        WorkerStatus.ACTIVE,
        WorkerStatus.IDLE
    );
    private static final String LOCKED_EVENT_MESSAGE = "Worker가 Embedding Job을 Claim했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final WorkerNodeRepository workerNodeRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobConverter embeddingJobConverter;
    private final IndexingWorkerProperties indexingWorkerProperties;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Worker가 처리할 다음 PENDING Job을 Claim한다.
     *
     * @param workerId Job 소유권을 받을 Worker 식별자
     * @return Claim 결과, 현재 처리 가능한 PENDING Job이 없으면 빈 값
     */
    public Optional<ClaimedEmbeddingJobResponse> claim(Long workerId) {
        // 1. Worker 검증과 Lease 계산이 같은 기준 시각을 사용하도록 요청 시각을 한 번만 계산한다.
        LocalDateTime claimedAt = LocalDateTime.now(clock);

        // 2. 존재하지 않는 Worker에는 Job 행 잠금을 시도하지 않고 즉시 404 예외를 반환한다.
        WorkerNode workerNode = workerNodeRepository.findById(workerId)
            .orElseThrow(() -> new DocGridException(ErrorCode.WORKER_NOT_FOUND));

        // 3. 저장 상태뿐 아니라 마지막 Heartbeat를 반영한 실질 상태가 ACTIVE/IDLE인지 확인한다.
        validateClaimable(workerNode, claimedAt);

        // 4. 시험 프로필의 문서 버전 범위가 있으면 그 Queue만 조회한다. 기본 운영 경로는 그대로 유지한다.
        Long documentVersionIdFilter = indexingWorkerProperties.getDocumentVersionIdFilter();
        Optional<EmbeddingJob> candidate = documentVersionIdFilter == null
            ? embeddingJobRepository.findNextPendingForUpdate(claimedAt)
            : embeddingJobRepository.findNextPendingForDocumentVersionForUpdate(claimedAt, documentVersionIdFilter);

        // 5. 잠금을 획득한 Job이 있을 때만 Lease 발급 흐름을 계속한다.
        return candidate
            .map(job -> claim(job, workerNode, claimedAt));
    }

    private ClaimedEmbeddingJobResponse claim(
        EmbeddingJob embeddingJob,
        WorkerNode workerNode,
        LocalDateTime claimedAt
    ) {
        // 1. 재Claim 시 이전 소유권과 구분할 수 있도록 매 Claim마다 새로운 UUID Token을 발급한다.
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime lockExpiresAt = claimedAt.plus(indexingWorkerProperties.getLeaseDuration());

        // 2. 상태와 소유 Worker, Token, Lease 시간을 Entity의 단일 상태 전이로 함께 변경한다.
        embeddingJob.claim(workerNode, claimToken, claimedAt, lockExpiresAt);

        // 3. Job 변경과 같은 Transaction에 LOCKED 이벤트를 저장해 상태 이력의 부분 누락을 방지한다.
        indexingEventRepository.save(IndexingEvent.builder()
            .embeddingJob(embeddingJob)
            .eventType(IndexingEventType.LOCKED)
            .fromStatus(EmbeddingJobStatus.PENDING.name())
            .toStatus(EmbeddingJobStatus.PROCESSING.name())
            .message(LOCKED_EVENT_MESSAGE)
            .occurredAt(claimedAt)
            .build());

        // 4. 대시보드가 최신 집계를 다시 계산하도록 상태 전이를 알린다. AFTER_COMMIT 구독자만
        //    반응하므로 이 Transaction이 실제로 커밋된 뒤에만 push로 이어진다.
        applicationEventPublisher.publishEvent(new EmbeddingJobStatusChangedEvent(embeddingJob.getId()));

        // 5. Transaction 안에서 LAZY 연관 식별자를 읽어 Claim 결과 DTO를 완성한다.
        return embeddingJobConverter.toClaimedResponse(embeddingJob);
    }

    /**
     * 저장 상태와 Heartbeat를 함께 계산한 Worker의 실질 상태가 Claim 가능한지 검증한다.
     */
    private void validateClaimable(WorkerNode workerNode, LocalDateTime claimedAt) {
        // 1. DEAD 기준 시각과 같거나 오래된 Heartbeat는 만료로 처리한다.
        LocalDateTime heartbeatDeadline = claimedAt.minus(indexingWorkerProperties.getDeadThreshold());

        // 2. 아직 DB에 DEAD가 반영되지 않았더라도 실질 상태가 만료되었으면 새 Job을 배정하지 않는다.
        WorkerStatus effectiveStatus = workerNode.resolveEffectiveStatus(heartbeatDeadline);
        if (!CLAIMABLE_WORKER_STATUSES.contains(effectiveStatus)) {
            throw new DocGridException(ErrorCode.WORKER_NOT_AVAILABLE);
        }
    }
}
