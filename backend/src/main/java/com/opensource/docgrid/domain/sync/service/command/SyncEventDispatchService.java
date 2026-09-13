package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventHandlerRegistry;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent.Outcome;

import lombok.RequiredArgsConstructor;

/**
 * 현재 Claim을 재검증한 뒤 Handler 부작용과 Event 완료를 하나의 독립 Transaction에서 확정한다.
 *
 * <p>Handler가 실패하면 전체 Transaction이 Rollback되므로 Event는 PROCESSING에 남고, 호출 Scheduler가
 * 별도 실패 전이 Service로 Retry를 예약한다. Handler가 Bulk Update로 영속성 Context를
 * 초기화해도 완료 전이 직전에 Event를 다시 잠그고 관리 상태를 회복한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventDispatchService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventHandlerRegistry syncEventHandlerRegistry;
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Claim된 Outbox Event의 Handler 부작용과 완료 전이를 독립 트랜잭션으로 실행한다.
     *
     * @param claimedEvent Poller가 획득한 Event ID와 Claim Token의 불변 Snapshot
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(ClaimedSyncEvent claimedEvent) {
        // 1. Handler 실행 전 현재 Claim의 소유권을 검증한다.
        LocalDateTime dispatchedAt = LocalDateTime.now(clock);
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(claimedEvent.eventId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        validateOwnership(event, claimedEvent, dispatchedAt);

        // 2. Handler 부작용을 Event 완료와 같은 Commit 경계에서 수행한다.
        syncEventHandlerRegistry.handle(event);

        // 3. Handler가 Context를 clear했을 수 있으므로 관리되는 Event를 다시 확보한다.
        LocalDateTime completedAt = LocalDateTime.now(clock);
        SyncOutboxEvent completionEvent = syncOutboxEventRepository
            .findByEventIdForUpdate(claimedEvent.eventId())
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));
        validateOwnership(completionEvent, claimedEvent, completedAt);

        // 4. Attempt 성공과 Event 완료를 함께 확정해 서로 다른 결과가 남지 않게 한다.
        syncEventDeliveryAttemptService.succeed(
            completionEvent.getEventId(),
            claimedEvent.claimToken(),
            completedAt
        );
        completionEvent.complete(claimedEvent.claimToken(), completedAt);

        // 5. Handler 부작용과 완료 상태가 함께 커밋된 뒤에만 처리 성공 Counter를 기록한다.
        applicationEventPublisher.publishEvent(new SyncEventAttemptMetricEvent(Outcome.PROCESSED));
    }

    /**
     * Event가 아직 같은 Token의 PROCESSING Claim이고 작업 시각까지 Lease가 유효한지 확인한다.
     */
    private void validateOwnership(
        SyncOutboxEvent event,
        ClaimedSyncEvent claimedEvent,
        LocalDateTime operatedAt
    ) {
        if (event.getStatus() != SyncEventStatus.PROCESSING
            || !Objects.equals(event.getClaimToken(), claimedEvent.claimToken())
            || event.getLockExpiresAt() == null
            || !event.getLockExpiresAt().isAfter(operatedAt)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID);
        }
    }
}
