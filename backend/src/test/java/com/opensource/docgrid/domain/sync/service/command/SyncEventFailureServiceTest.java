package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Sync Handler 실패가 Retry 또는 최종 실패로 확정될 때 대응 metric event를 발행하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventFailureService 메트릭 테스트")
class SyncEventFailureServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("68c12639-7c1f-4740-8db9-29a341983251");
    private static final UUID CLAIM_TOKEN = UUID.fromString("a5492111-f005-40cf-9f2f-f72fd63f45f7");
    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 9, 13, 19, 0);
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-13T19:00:00Z"),
        ZoneOffset.UTC
    );

    @Mock private SyncOutboxEventRepository repository;
    @Mock private SyncEventRetrySchedule retrySchedule;
    @Mock private SyncEventDeliveryAttemptService attemptService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    @Test
    @DisplayName("남은 실행 기회가 있으면 RETRY_SCHEDULED event를 발행한다")
    void recordFailure_retryable_publishesRetryScheduledMetric() {
        SyncOutboxEvent event = claimedEvent(3);
        given(repository.findByEventIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));
        given(retrySchedule.nextAvailableAt(event, FAILED_AT)).willReturn(FAILED_AT.plusSeconds(5));
        SyncEventFailureService service = service();

        service.recordFailure(EVENT_ID, CLAIM_TOKEN, "HANDLER_ERROR", "temporary failure");

        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        then(applicationEventPublisher).should()
            .publishEvent(new SyncEventAttemptMetricEvent(Outcome.RETRY_SCHEDULED));
    }

    @Test
    @DisplayName("마지막 실행 기회를 소진하면 TERMINAL_FAILURE event를 발행한다")
    void recordFailure_exhausted_publishesTerminalFailureMetric() {
        SyncOutboxEvent event = claimedEvent(1);
        given(repository.findByEventIdForUpdate(EVENT_ID)).willReturn(Optional.of(event));
        SyncEventFailureService service = service();

        service.recordFailure(EVENT_ID, CLAIM_TOKEN, "HANDLER_ERROR", "permanent failure");

        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.FAILED);
        then(applicationEventPublisher).should()
            .publishEvent(new SyncEventAttemptMetricEvent(Outcome.TERMINAL_FAILURE));
    }

    private SyncEventFailureService service() {
        return new SyncEventFailureService(
            repository,
            retrySchedule,
            attemptService,
            CLOCK,
            applicationEventPublisher
        );
    }

    private SyncOutboxEvent claimedEvent(int maxRetryCount) {
        SyncOutboxEvent event = SyncOutboxEvent.builder()
            .eventId(EVENT_ID)
            .idempotencyKey("sync-failure-service-test-" + maxRetryCount)
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .availableAt(FAILED_AT.minusMinutes(1))
            .occurredAt(FAILED_AT.minusMinutes(1))
            .maxRetryCount(maxRetryCount)
            .build();
        event.claim("dispatcher", CLAIM_TOKEN, FAILED_AT.minusSeconds(1), FAILED_AT.plusMinutes(1));
        return event;
    }
}
