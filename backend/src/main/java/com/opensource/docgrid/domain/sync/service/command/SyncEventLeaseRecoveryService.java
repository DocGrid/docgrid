package com.opensource.docgrid.domain.sync.service.command;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventRetrySchedule;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent.Outcome;

import lombok.RequiredArgsConstructor;

/**
 * 만료 PROCESSING Event 후보를 독립 Transaction에서 다시 잠그고 Retry 또는 최종 실패로 회수한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventLeaseRecoveryService {

    private static final String LEASE_EXPIRED_CODE = "SYNC_LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE = "Sync Dispatcher Lease가 만료되어 실행을 회수했습니다.";

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventRetrySchedule syncEventRetrySchedule;
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 만료 후보 Event를 다시 확인해 실제로 만료된 현재 Claim만 회수한다.
     *
     * @param eventId Snapshot 조회에서 발견한 만료 후보 Event 식별자
     * @param recoveredAt 후보 조회와 재검증에 사용할 동일한 회수 기준 시각
     * @return 실제 회수 여부와 회수 후 Queue 상태
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryResult recover(UUID eventId, LocalDateTime recoveredAt) {
        // 1. 후보를 다시 잠그고 아직 PROCESSING이며 Lease가 만료된 경우에만 회수 대상으로 채택한다.
        Optional<SyncOutboxEvent> candidate = syncOutboxEventRepository
            .findExpiredByEventIdForUpdateSkipLocked(eventId, recoveredAt);

        // 2. 다른 Dispatcher가 이미 처리·회수했거나 잠금을 보유한 후보는 정상적인 건너뜀으로 응답한다.
        if (candidate.isEmpty()) {
            return new RecoveryResult(eventId, false, null);
        }

        // 3. 만료 Claim의 Delivery Attempt를 동일한 고정 오류로 실패 종결한다.
        SyncOutboxEvent event = candidate.get();
        syncEventDeliveryAttemptService.fail(
            event.getEventId(),
            event.getClaimToken(),
            LEASE_EXPIRED_CODE,
            LEASE_EXPIRED_MESSAGE,
            recoveredAt
        );

        // 4. Queue Event는 Retry 정책에 따라 재예약하거나 최대 횟수 도달 시 최종 실패로 전환한다.
        event.recoverExpiredLease(
            LEASE_EXPIRED_CODE,
            LEASE_EXPIRED_MESSAGE,
            recoveredAt,
            syncEventRetrySchedule.nextAvailableAt(event, recoveredAt)
        );

        // 5. 최종 실패는 즉시 조치 경보 대상이며, 재예약된 Lease 만료는 회수 활동으로 구분한다.
        Outcome outcome = event.getStatus() == SyncEventStatus.FAILED
            ? Outcome.TERMINAL_FAILURE
            : Outcome.LEASE_RECOVERED;
        applicationEventPublisher.publishEvent(new SyncEventAttemptMetricEvent(outcome));

        // 6. Scheduler가 회수 결과를 집계할 수 있도록 변경 후 상태를 반환한다.
        return new RecoveryResult(eventId, true, event.getStatus());
    }

    /**
     * 만료 후보가 실제 상태 전이를 수행했는지와 회수 후 상태를 Scheduler에 전달한다.
     */
    public record RecoveryResult(UUID eventId, boolean recovered, SyncEventStatus status) {
    }
}
