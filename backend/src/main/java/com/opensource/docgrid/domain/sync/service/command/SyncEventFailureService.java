package com.opensource.docgrid.domain.sync.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventRetrySchedule;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent.Outcome;

import lombok.RequiredArgsConstructor;

/**
 * Rollback된 Handler 실행의 실패를 별도 Transaction에서 Retry 또는 최종 실패 상태로 기록한다.
 *
 * <p>호출자는 민감한 예외 Message 대신 안정적인 오류 코드와 제한된 진단 문구만 전달해야 한다.
 */
@Service
@RequiredArgsConstructor
public class SyncEventFailureService {

    private final SyncOutboxEventRepository syncOutboxEventRepository;
    private final SyncEventRetrySchedule syncEventRetrySchedule;
    private final SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Handler 실패를 현재 Claim의 Delivery Attempt와 Queue 상태에 함께 기록한다.
     *
     * <p>호출한 Dispatch Transaction은 이미 Rollback되므로 새 Transaction에서 처리하며, Retry 한도에
     * 도달하지 않았으면 Backoff 시각을 예약하고 도달했으면 최종 FAILED로 종결한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID eventId, UUID claimToken, String errorCode, String errorMessage) {
        // 1. 잠금 획득 이후의 같은 시각을 소유권 만료 검증과 실패 기록에 사용한다.
        LocalDateTime failedAt = LocalDateTime.now(clock);

        // 2. Event 행을 잠가 성공 처리나 Lease 복구와의 경쟁을 직렬화한다.
        SyncOutboxEvent event = syncOutboxEventRepository.findByEventIdForUpdate(eventId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SYNC_EVENT_NOT_FOUND));

        // 3. 현재 Claim의 유효한 Lease인지 확인한 뒤 대응 Delivery Attempt를 실패로 닫는다.
        validateOwnership(event, claimToken, failedAt);
        syncEventDeliveryAttemptService.fail(eventId, claimToken, errorCode, errorMessage, failedAt);

        // 4. 이번 실패가 허용 횟수를 채우면 다시 Claim되지 않는 최종 상태로 종결한다.
        if (event.getRetryCount() + 1 >= event.getMaxRetryCount()) {
            event.markFailed(claimToken, errorCode, errorMessage, failedAt);
            applicationEventPublisher.publishEvent(
                new SyncEventAttemptMetricEvent(Outcome.TERMINAL_FAILURE)
            );
            return;
        }

        // 5. 재시도 가능하면 실패 정보와 다음 Claim 가능 시각을 함께 저장하고 현재 Lease를 해제한다.
        event.scheduleRetry(
            claimToken,
            errorCode,
            errorMessage,
            failedAt,
            syncEventRetrySchedule.nextAvailableAt(event, failedAt)
        );
        applicationEventPublisher.publishEvent(
            new SyncEventAttemptMetricEvent(Outcome.RETRY_SCHEDULED)
        );
    }

    /**
     * 실패를 보고한 Token이 아직 만료되지 않은 현재 PROCESSING Claim인지 검증한다.
     */
    private void validateOwnership(
        SyncOutboxEvent event,
        UUID claimToken,
        LocalDateTime failedAt
    ) {
        // 상태, Claim 세대와 Lease를 함께 비교해 과거 Handler가 새 소유자의 Event를 덮지 못하게 한다.
        if (event.getStatus() != SyncEventStatus.PROCESSING
            || !Objects.equals(event.getClaimToken(), claimToken)
            || event.getLockExpiresAt() == null
            || !event.getLockExpiresAt().isAfter(failedAt)) {
            throw new DocGridException(ErrorCode.SYNC_EVENT_OWNERSHIP_INVALID);
        }
    }
}
