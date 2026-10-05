package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncAggregateType;
import com.opensource.docgrid.domain.sync.enums.SyncEventType;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.lifecycle.SyncEventPollingScheduler;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

/**
 * 별도 OpenSQL 시험 DB에서 실제 Dispatcher의 Claim·Handler·완료 경계를 검증한다.
 *
 * <p>파일 저장 호출만 대체하며 기존 DocGrid DB, GCS 객체, 앱 A/B VM은 건드리지 않는다.
 * 전용 DB 이름과 필수 인증 환경 변수가 없으면 Spring Context 생성 전에 실패한다.
 */
@Tag("opensql-outbox-isolated")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("OpenSQL 격리 DB Outbox Dispatcher 통합 테스트")
class OpenSqlIsolatedOutboxDispatcherIntegrationTest {

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private SyncOutboxEventRepository eventRepository;
    @Autowired private SyncEventPollingScheduler dispatcher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @MockitoBean
    private FileStorageService fileStorageService;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        // 1. 실수로 기존 운영 DB에서 Flyway나 시험 쓰기를 실행하지 않도록 시작 전에 대상을 제한한다.
        String name = required("OUTBOX_ISOLATED_DB_NAME");
        if (!name.matches("docgrid_outbox_439_[0-9]{8}")) {
            throw new IllegalStateException("별도 Outbox 시험 DB 이름이 아닙니다.");
        }
        String url = "jdbc:postgresql://127.0.0.1:15432/" + name + "?currentSchema=public&sslmode=disable";

        // 2. Flyway는 migration 계정, 애플리케이션은 최소 권한 계정으로 같은 시험 DB에 붙인다.
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("OPENSQL_APP_USER"));
        registry.add("spring.datasource.password", () -> required("OPENSQL_APP_PASSWORD"));
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> required("OPENSQL_MIGRATION_USER"));
        registry.add("spring.flyway.password", () -> required("OPENSQL_MIGRATION_PASSWORD"));
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");

        // 3. 자동 Polling과 다른 Worker는 멈추고 이 테스트가 Polling 한 주기를 직접 실행한다.
        registry.add("sync.dispatcher.enabled", () -> "true");
        registry.add("sync.dispatcher.polling-interval", () -> "1h");
        registry.add("indexing.worker.enabled", () -> "false");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("필수 격리 시험 환경 변수가 없습니다: " + name);
        }
        return value;
    }

    @BeforeEach
    void prepareStorage() {
        assertThat(jdbcTemplate.queryForObject("SELECT pg_is_in_recovery()", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_outbox_events WHERE status = 'PENDING'", Integer.class
        )).isZero();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.MINIO,
                "isolated-test-bucket",
                "outbox-isolated/" + UUID.randomUUID()
            ));
    }

    @Test
    @DisplayName("문서 업로드 Event를 Dispatcher가 처리하고 기존 Job을 중복 생성하지 않는다")
    void dispatchesDocumentVersionEventWithoutDuplicatingJob() {
        // 1. 시험 DB에만 문서·버전·Outbox Event·Embedding Job을 같은 업로드 트랜잭션으로 기록한다.
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
        String marker = "outbox-isolated-" + UUID.randomUUID();
        DocumentUploadResponse uploaded = documentUploadFacade.upload(
            userId,
            new DocumentUploadRequest(
                new MockMultipartFile("file", marker + ".txt", "text/plain", marker.getBytes()),
                marker,
                "OpenSQL 격리 Dispatcher 검증",
                VisibilityType.PRIVATE
            )
        );
        UUID eventId = jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            uploaded.embeddingJobId()
        );
        assertThat(eventRepository.findByEventId(eventId).orElseThrow().getStatus())
            .isEqualTo(SyncEventStatus.PENDING);

        // 2. 조건부로 등록된 실제 Polling Scheduler의 Claim → Handler → 완료 경로를 한 번 실행한다.
        dispatcher.poll();

        // 3. Event·Attempt의 영속 상태와 Job 출처의 유일성을 DB에서 직접 대조한다.
        assertThat(eventRepository.findByEventId(eventId).orElseThrow().getStatus())
            .isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_event_delivery_attempts WHERE event_id = ? AND status = 'SUCCEEDED'",
            Integer.class, eventId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_jobs WHERE source_event_id = ?",
            Integer.class, eventId
        )).isOne();
    }

    @Test
    @DisplayName("Handler 실패를 별도 Attempt로 남기고 다음 Polling에서 재처리한다")
    void retriesFailedEventAfterItsPayloadIsRepaired() {
        // 1. 시험 DB에만 잘못된 Payload의 권한 캐시 Event를 넣어 Handler 실패를 결정적으로 만든다.
        UUID eventId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);
        eventRepository.saveAndFlush(SyncOutboxEvent.builder()
            .eventId(eventId)
            .idempotencyKey("isolated-retry:" + eventId)
            .aggregateType(SyncAggregateType.PERMISSION)
            .aggregateId(9_999_999_998L)
            .eventType(SyncEventType.PERMISSION_CACHE_REFRESH_REQUESTED)
            .payloadJson("{}")
            .occurredAt(now)
            .availableAt(now)
            .maxRetryCount(2)
            .build());
        dispatcher.poll();

        // 2. Rollback된 Handler와 별도 Transaction의 실패 기록·Retry 예약을 확인한다.
        SyncOutboxEvent retriable = eventRepository.findByEventId(eventId).orElseThrow();
        assertThat(retriable.getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertThat(retriable.getRetryCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_event_delivery_attempts WHERE event_id = ? AND status = 'FAILED'",
            Integer.class, eventId
        )).isOne();

        // 3. 시험 Event만 복구하고 다음 Polling을 실행해 최종 상태와 Attempt 두 건을 대조한다.
        assertThat(jdbcTemplate.update("""
            UPDATE sync_outbox_events
               SET payload_json = ?, available_at = ?
             WHERE event_id = ?
            """,
            "{\"sourceType\":\"DIRECT_DOCUMENT_PERMISSION\",\"operation\":\"REVOKED\"}",
            LocalDateTime.now(clock).minusSeconds(10),
            eventId))
            .isOne();
        dispatcher.poll();

        assertThat(eventRepository.findByEventId(eventId).orElseThrow().getStatus())
            .isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_event_delivery_attempts WHERE event_id = ? AND status = 'SUCCEEDED'",
            Integer.class, eventId
        )).isOne();
    }
}
