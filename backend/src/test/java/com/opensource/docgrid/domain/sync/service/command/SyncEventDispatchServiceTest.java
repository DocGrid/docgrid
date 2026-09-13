package com.opensource.docgrid.domain.sync.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventHandlerRegistry;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent;
import com.opensource.docgrid.global.observability.SyncEventAttemptMetricEvent.Outcome;

/**
 * Handler 실행과 Outbox Event 완료 전이가 같은 Dispatch 경계에서 수행되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventDispatchService 단위 테스트")
class SyncEventDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock private SyncOutboxEventRepository syncOutboxEventRepository;
    @Mock private SyncEventHandlerRegistry syncEventHandlerRegistry;
    @Mock private SyncEventDeliveryAttemptService syncEventDeliveryAttemptService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private SyncEventDispatchService service;
    private SyncOutboxEvent event;
    private SyncOutboxEvent completionEvent;
    private ClaimedSyncEvent claim;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZONE_ID);
        service = new SyncEventDispatchService(
            syncOutboxEventRepository,
            syncEventHandlerRegistry,
            syncEventDeliveryAttemptService,
            clock,
            applicationEventPublisher
        );
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE_ID);
        UUID eventId = UUID.randomUUID();
        event = event(eventId, now);
        completionEvent = event(eventId, now);
        UUID claimToken = UUID.randomUUID();
        event.claim("dispatcher", claimToken, now, now.plusSeconds(30));
        completionEvent.claim("dispatcher", claimToken, now, now.plusSeconds(30));
        claim = new ClaimedSyncEvent(event.getEventId(), claimToken);
        given(syncOutboxEventRepository.findByEventIdForUpdate(event.getEventId()))
            .willReturn(Optional.of(event), Optional.of(completionEvent));
    }

    @Test
    @DisplayName("Handler 성공 후 Event를 다시 조회해 PROCESSED로 완료한다")
    void dispatch_reloadsAndCompletesEvent_afterHandlerSucceeds() {
        service.dispatch(claim);

        then(syncEventHandlerRegistry).should().handle(event);
        then(syncOutboxEventRepository).should(times(2)).findByEventIdForUpdate(event.getEventId());
        then(syncEventDeliveryAttemptService).should().succeed(
            event.getEventId(),
            claim.claimToken(),
            LocalDateTime.ofInstant(NOW, ZONE_ID)
        );
        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        assertThat(completionEvent.getStatus()).isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(completionEvent.getProcessedAt()).isNotNull();
        then(applicationEventPublisher).should()
            .publishEvent(new SyncEventAttemptMetricEvent(Outcome.PROCESSED));
    }

    @Test
    @DisplayName("Handler가 실패하면 완료 전이를 실행하지 않는다")
    void dispatch_doesNotComplete_whenHandlerFails() {
        doThrow(new IllegalStateException("handler failure"))
            .when(syncEventHandlerRegistry).handle(event);

        assertThatThrownBy(() -> service.dispatch(claim))
            .isInstanceOf(IllegalStateException.class);
        then(syncEventDeliveryAttemptService).should(never()).succeed(
            event.getEventId(),
            claim.claimToken(),
            LocalDateTime.ofInstant(NOW, ZONE_ID)
        );
        assertThat(event.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        assertThat(event.getProcessedAt()).isNull();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    private SyncOutboxEvent event(UUID eventId, LocalDateTime now) {
        return SyncOutboxEvent.builder()
            .eventId(eventId)
            .idempotencyKey("dispatch:" + UUID.randomUUID())
            .aggregateType(SyncAggregateType.DOCUMENT_VERSION)
            .aggregateId(1L)
            .aggregateVersion(1L)
            .eventType(SyncEventType.DOCUMENT_VERSION_CREATED)
            .payloadJson("{\"embeddingModelId\":1}")
            .availableAt(now)
            .occurredAt(now)
            .maxRetryCount(3)
            .build();
    }
}
