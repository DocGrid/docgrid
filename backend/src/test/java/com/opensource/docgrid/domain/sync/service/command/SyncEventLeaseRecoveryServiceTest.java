package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventRetrySchedule;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent.Outcome;

/**
 * 만료 Sync Lease 회수가 재예약과 최종 실패 결과에 맞는 metric event를 발행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventLeaseRecoveryService 메트릭 테스트")
class SyncEventLeaseRecoveryServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("8db4bc37-8148-4bb4-b471-d89722d9013f");
    private static final UUID CLAIM_TOKEN = UUID.fromString("4b65a076-0677-47b2-a21f-d3499ef1d09b");
    private static final LocalDateTime RECOVERED_AT = LocalDateTime.of(2026, 9, 13, 19, 0);

    @Mock private SyncOutboxEventRepository repository;
    @Mock private SyncEventRetrySchedule retrySchedule;
    @Mock private SyncEventDeliveryAttemptService attemptService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("남은 실행 기회가 있는 만료 Lease는 LEASE_RECOVERED event를 발행한다")
    void recover_retryable_publishesLeaseRecoveredMetric() {
        SyncOutboxEvent event = expiredEvent(3);
        given(repository.findExpiredByEventIdForUpdateSkipLocked(EVENT_ID, RECOVERED_AT))
            .willReturn(Optional.of(event));
        given(retrySchedule.nextAvailableAt(event, RECOVERED_AT)).willReturn(RECOVERED_AT.plusSeconds(5));
        SyncEventLeaseRecoveryService service = service();

        SyncEventLeaseRecoveryService.RecoveryResult result = service.recover(EVENT_ID, RECOVERED_AT);

        assertThat(result.recovered()).isTrue();
        assertThat(result.status()).isEqualTo(SyncEventStatus.PENDING);
        then(applicationEventPublisher).should()
            .publishEvent(new SyncEventAttemptMetricEvent(Outcome.LEASE_RECOVERED));
    }

    @Test
    @DisplayName("마지막 실행 기회를 소진한 만료 Lease는 TERMINAL_FAILURE event를 발행한다")
    void recover_exhausted_publishesTerminalFailureMetric() {
        SyncOutboxEvent event = expiredEvent(1);
        given(repository.findExpiredByEventIdForUpdateSkipLocked(EVENT_ID, RECOVERED_AT))
            .willReturn(Optional.of(event));
        given(retrySchedule.nextAvailableAt(event, RECOVERED_AT)).willReturn(RECOVERED_AT.plusSeconds(5));
        SyncEventLeaseRecoveryService service = service();

        SyncEventLeaseRecoveryService.RecoveryResult result = service.recover(EVENT_ID, RECOVERED_AT);

        assertThat(result.recovered()).isTrue();
        assertThat(result.status()).isEqualTo(SyncEventStatus.FAILED);
        then(applicationEventPublisher).should()
            .publishEvent(new SyncEventAttemptMetricEvent(Outcome.TERMINAL_FAILURE));
    }

    private SyncEventLeaseRecoveryService service() {
        return new SyncEventLeaseRecoveryService(
            repository,
            retrySchedule,
            attemptService,
            applicationEventPublisher
        );
    }

    private SyncOutboxEvent expiredEvent(int maxRetryCount) {
        SyncOutboxEvent event = SyncOutboxEvent.builder()
            .eventId(EVENT_ID)
            .idempotencyKey("sync-lease-recovery-test-" + maxRetryCount)
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .availableAt(RECOVERED_AT.minusMinutes(2))
            .occurredAt(RECOVERED_AT.minusMinutes(2))
            .maxRetryCount(maxRetryCount)
            .build();
        event.claim(
            "dispatcher",
            CLAIM_TOKEN,
            RECOVERED_AT.minusMinutes(1),
            RECOVERED_AT.minusSeconds(1)
        );
        return event;
    }
}
