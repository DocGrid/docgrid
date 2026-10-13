package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 로컬 PostgreSQL 원장과 MockMvc로 최초 업로드의 재전송·충돌·동시 소유권·만료를 검증한다.
 *
 * <p>파일 저장소는 시험용 Port로 대체하며 실제 GCS 또는 Cloud Run 장애는 포함하지 않는다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("최초 업로드 멱등 처리 통합 테스트")
class InitialUploadIdempotencyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private InitialUploadRequestService requestService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private FileStorageService fileStorageService;

    private String key;
    private String title;
    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        key = "upload-" + suffix;
        title = "idempotency-" + suffix;
        userId = jdbcTemplate.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM initial_upload_requests WHERE user_id = ? AND request_key = ?", userId, key);
        List<Long> documentIds = jdbcTemplate.queryForList(
            "SELECT id FROM documents WHERE title = ?", Long.class, title
        );
        for (Long documentId : documentIds) {
            List<Long> versionIds = jdbcTemplate.queryForList(
                "SELECT id FROM document_versions WHERE document_id = ?", Long.class, documentId
            );
            for (Long versionId : versionIds) {
                Long fileObjectId = jdbcTemplate.queryForObject(
                    "SELECT file_object_id FROM document_versions WHERE id = ?", Long.class, versionId
                );
                jdbcTemplate.update("DELETE FROM embedding_jobs WHERE document_version_id = ?", versionId);
                jdbcTemplate.update(
                    "DELETE FROM sync_outbox_events WHERE aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ?",
                    versionId
                );
                jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", documentId);
                jdbcTemplate.update("DELETE FROM document_versions WHERE id = ?", versionId);
                jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
                jdbcTemplate.update("DELETE FROM file_objects WHERE id = ?", fileObjectId);
            }
        }
    }

    @Test
    @DisplayName("응답 유실 후 같은 키와 입력을 재전송하면 원본 201과 식별자를 반환한다")
    void replayReturnsOriginalResult() throws Exception {
        stubStorage();

        // 1. 첫 서버 응답을 사용자가 받지 못한 것으로 취급한 뒤 동일 입력을 재전송한다.
        MvcResult first = send("same-content", title);
        MvcResult replay = send("same-content", title);

        // 2. 두 응답의 식별자와 DB의 논리 문서·Job·Outbox 건수를 대조한다.
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstData = data(first);
        assertThat(data(replay)).isEqualTo(firstData);
        assertThat(count("documents", "title = ?", title)).isOne();
        assertThat(count("embedding_jobs", "document_version_id = ?", firstData.path("documentVersionId").asLong()))
            .isOne();
        assertThat(count("sync_outbox_events", "aggregate_id = ?", firstData.path("documentVersionId").asLong()))
            .isOne();
        verify(fileStorageService, times(1)).store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 키에서 파일 또는 메타데이터가 바뀌면 409를 반환한다")
    void changedInputConflicts() throws Exception {
        stubStorage();
        assertThat(send("same-content", title).getResponse().getStatus()).isEqualTo(201);

        MvcResult changedFile = send("other-content", title);
        MvcResult changedTitle = send("same-content", title + "-changed");

        assertThat(changedFile.getResponse().getStatus()).isEqualTo(409);
        assertThat(changedTitle.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(changedFile.getResponse().getContentAsString()).path("code").asText())
            .isEqualTo("DOCUMENT-UPLOAD-003");
        assertThat(count("documents", "title = ?", title)).isOne();
        verify(fileStorageService, times(1)).store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("유효하지 않은 키는 저장소 작업 전에 400으로 거부한다")
    void invalidKeyDoesNotStore() throws Exception {
        key = "invalid key";
        MvcResult invalid = send("same-content", title);

        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(invalid.getResponse().getContentAsString()).path("code").asText())
            .isEqualTo("DOCUMENT-UPLOAD-002");
        verify(fileStorageService, times(0)).store(any(InputStream.class), anyLong(), anyString(), anyString());
        assertThat(count("documents", "title = ?", title)).isZero();
    }

    @Test
    @DisplayName("확인된 저장 실패 뒤 같은 키를 즉시 재시도할 수 있다")
    void failedStorageReleasesClaim() throws Exception {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willThrow(new DocGridException(ErrorCode.FILE_STORAGE_FAILED))
            .willReturn(storedFile());

        MvcResult failed = send("same-content", title);
        MvcResult retried = send("same-content", title);

        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("documents", "title = ?", title)).isOne();
        verify(fileStorageService, times(2)).store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("DB 생성 실패는 완료 원장을 남기지 않고 같은 키의 재시도를 허용한다")
    void failedDatabaseTransactionDoesNotCompleteClaim() throws Exception {
        stubStorage();
        jdbcTemplate.update("UPDATE embedding_models SET is_active = FALSE WHERE is_active = TRUE AND is_searchable = TRUE");
        MvcResult failed;
        try {
            failed = send("same-content", title);
        } finally {
            jdbcTemplate.update("""
                UPDATE embedding_models SET is_active = TRUE
                 WHERE model_name = 'BAAI/bge-m3' AND model_version = '1.0'
                """);
        }

        assertThat(failed.getResponse().getStatus()).isEqualTo(500);
        assertThat(count("documents", "title = ?", title)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM initial_upload_requests WHERE user_id = ? AND request_key = ?",
            String.class, userId, key
        )).isEqualTo("IN_PROGRESS");

        MvcResult retried = send("same-content", title);
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("documents", "title = ?", title)).isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM initial_upload_requests WHERE user_id = ? AND request_key = ?",
            String.class, userId, key
        )).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("동시 동일 키 요청은 한 요청만 저장하고 진행 중 응답 뒤 원본 결과로 수렴한다")
    void concurrentRetryUsesSingleOwner() throws Exception {
        CountDownLatch storing = new CountDownLatch(1);
        CountDownLatch continueStorage = new CountDownLatch(1);
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                storing.countDown();
                if (!continueStorage.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("storage wait timed out");
                }
                return storedFile();
            });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> send("same-content", title));
            assertThat(storing.await(10, TimeUnit.SECONDS)).isTrue();

            MvcResult inProgress = send("same-content", title);
            assertThat(inProgress.getResponse().getStatus()).isEqualTo(409);
            assertThat(inProgress.getResponse().getHeader("Retry-After")).isEqualTo("5");

            continueStorage.countDown();
            MvcResult completed = first.get(15, TimeUnit.SECONDS);
            MvcResult replay = send("same-content", title);
            assertThat(completed.getResponse().getStatus()).isEqualTo(201);
            assertThat(data(replay)).isEqualTo(data(completed));
            assertThat(count("documents", "title = ?", title)).isOne();
            verify(fileStorageService, times(1)).store(any(InputStream.class), anyLong(), anyString(), anyString());
        } finally {
            continueStorage.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("만료된 소유권을 새 시도가 점유하면 이전 시도의 완료가 거부된다")
    void expiredOwnerCannotCompleteAfterTakeover() {
        String fingerprint = "a".repeat(64);
        InitialUploadRequestService.Claim first = requestService.acquire(userId, key, fingerprint);
        jdbcTemplate.update("""
            UPDATE initial_upload_requests SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
             WHERE user_id = ? AND request_key = ?
            """, userId, key);
        InitialUploadRequestService.Claim second = requestService.acquire(userId, key, fingerprint);
        assertThat(second.token()).isNotEqualTo(first.token());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DocumentUploadResponse response = new DocumentUploadResponse(
            1L, 2L, 3L, 4L, DocumentStatus.UPLOADED, EmbeddingJobStatus.PENDING
        );
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> requestService.complete(first, response)))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM initial_upload_requests WHERE user_id = ? AND request_key = ?",
            String.class, userId, key
        )).isEqualTo("IN_PROGRESS");
    }

    private MvcResult send(String content, String requestTitle) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "sample.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8)
        );
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
            "user", "password", List.of()
        );
        auth.setDetails(userId);
        return mockMvc.perform(multipart("/api/documents")
                .file(file)
                .param("title", requestTitle)
                .param("description", "test")
                .param("visibility", "PRIVATE")
                .header("Idempotency-Key", key)
                .with(csrf())
                .with(authentication(auth)))
            .andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int count(String table, String where, Object value) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, value);
    }

    private void stubStorage() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(storedFile());
    }

    private StoredFile storedFile() {
        return new StoredFile(StorageProvider.LOCAL, "test-bucket", "documents/test/idempotency-" + key);
    }
}
