package com.opensource.docgrid.opensql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.InputStream;
import java.time.Duration;
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
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;

/**
 * 격리 OpenSQL DB에서 스케줄러의 자동 Polling이 Event를 소비하는지 검증한다.
 *
 * <p>테스트는 poll()을 직접 호출하지 않는다. GCP 앱 VM과 파일 저장소는 사용하지 않으며,
 * 지정된 별도 DB 이름이 없으면 Spring Context를 시작하지 않는다.
 */
@Tag("opensql-outbox-isolated")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("OpenSQL 격리 DB Outbox 자동 Polling 통합 테스트")
class OpenSqlIsolatedOutboxScheduledIntegrationTest {

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private SyncOutboxEventRepository eventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        // 1. 기본 DB에 Flyway가 실행되지 않도록 전용 이름과 루프백 주소를 먼저 확정한다.
        String name = required("OUTBOX_ISOLATED_DB_NAME");
        if (!name.matches("docgrid_outbox_439_[0-9]{8}")) {
            throw new IllegalStateException("별도 Outbox 시험 DB 이름이 아닙니다.");
        }
        String url = "jdbc:postgresql://127.0.0.1:15432/" + name + "?currentSchema=public&sslmode=disable";

        // 2. migration과 앱 계정은 같은 격리 DB에 서로 다른 권한으로 접속한다.
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("OPENSQL_APP_USER"));
        registry.add("spring.datasource.password", () -> required("OPENSQL_APP_PASSWORD"));
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> required("OPENSQL_MIGRATION_USER"));
        registry.add("spring.flyway.password", () -> required("OPENSQL_MIGRATION_PASSWORD"));
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");

        // 3. 실제 @Scheduled Polling만 켜고 문서 인덱싱 Worker는 시험에서 분리한다.
        registry.add("sync.dispatcher.enabled", () -> "true");
        registry.add("sync.dispatcher.polling-interval", () -> "1s");
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
                "outbox-scheduled/" + UUID.randomUUID()
            ));
    }

    @Test
    @DisplayName("자동 Polling이 새 문서 Event를 완료하고 연결 Job을 중복 생성하지 않는다")
    void scheduledPollingCompletesDocumentVersionEvent() {
        // 1. 문서 업로드가 전용 DB에 Event와 최초 Job을 같은 트랜잭션으로 만든다.
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
        String marker = "outbox-scheduled-" + UUID.randomUUID();
        DocumentUploadResponse uploaded = documentUploadFacade.upload(
            userId,
            new DocumentUploadRequest(
                new MockMultipartFile("file", marker + ".txt", "text/plain", marker.getBytes()),
                marker,
                "OpenSQL 자동 Polling 검증",
                VisibilityType.PRIVATE
            )
        );
        UUID eventId = jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            uploaded.embeddingJobId()
        );

        // 2. poll()을 호출하지 않고 @Scheduled 주기가 Event를 완료할 때까지 관찰한다.
        await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(eventRepository.findByEventId(eventId).orElseThrow().getStatus())
                .isEqualTo(SyncEventStatus.PROCESSED));

        // 3. 성공 Attempt와 Event 출처 Job을 DB에서 대조해 처리 중복을 배제한다.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_event_delivery_attempts WHERE event_id = ? AND status = 'SUCCEEDED'",
            Integer.class, eventId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_jobs WHERE source_event_id = ?",
            Integer.class, eventId
        )).isOne();
    }
}
