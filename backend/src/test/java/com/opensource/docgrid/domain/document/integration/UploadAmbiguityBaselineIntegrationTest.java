package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.FileHashService;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;

/**
 * 최초 업로드의 결과 불명 경계를 로컬 PostgreSQL과 시험용 저장소 Port로 재현한다.
 *
 * <p>MockMvc 응답 폐기와 DB 서비스 진입 전 중단은 네트워크 단절·프로세스 종료를 결정적으로
 * 모의한다. 실제 GCS 장애나 JVM 강제 종료를 실행한 시험으로 해석해서는 안 된다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("최초 업로드 결과 불명 기준선")
class UploadAmbiguityBaselineIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private FileHashService fileHashService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private FileStorageService fileStorageService;
    @MockitoSpyBean private DocumentUploadService documentUploadService;

    private static final String RUN_ID = System.getenv().getOrDefault(
        "UPLOAD_BASELINE_RUN_ID", "local-" + UUID.randomUUID()
    ).replaceAll("[^a-zA-Z0-9_-]", "_");

    private final List<StoredFile> stored = new CopyOnWriteArrayList<>();
    private final List<StoredFile> deleted = new CopyOnWriteArrayList<>();
    private String title;
    private String content;
    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        title = "upload-ambiguity-" + suffix;
        content = "upload-ambiguity-content-" + suffix;
        userId = jdbcTemplate.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
        doAnswer(invocation -> {
            deleted.add(invocation.getArgument(0));
            return null;
        }).when(fileStorageService).delete(any(StoredFile.class));
    }

    @AfterEach
    void tearDown() {
        List<Long> documentIds = jdbcTemplate.queryForList(
            "SELECT id FROM documents WHERE title = ?", Long.class, title
        );
        Set<Long> fileObjectIds = new HashSet<>();
        for (Long documentId : documentIds) {
            List<Long> versionIds = jdbcTemplate.queryForList(
                "SELECT id FROM document_versions WHERE document_id = ?", Long.class, documentId
            );
            for (Long versionId : versionIds) {
                fileObjectIds.add(jdbcTemplate.queryForObject(
                    "SELECT file_object_id FROM document_versions WHERE id = ?", Long.class, versionId
                ));
                jdbcTemplate.update("DELETE FROM embedding_jobs WHERE document_version_id = ?", versionId);
                jdbcTemplate.update(
                    "DELETE FROM sync_outbox_events WHERE aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ?",
                    versionId
                );
                jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", documentId);
                jdbcTemplate.update("DELETE FROM document_versions WHERE id = ?", versionId);
            }
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        fileObjectIds.forEach(id -> jdbcTemplate.update("DELETE FROM file_objects WHERE id = ?", id));
    }

    @Test
    @DisplayName("커밋 후 응답을 폐기하고 재전송하면 문서와 Job이 두 개 생긴다")
    void responseLostAfterCommit_thenRetryCreatesSecondDocument() throws Exception {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                StoredFile candidate = new StoredFile(StorageProvider.LOCAL, "test-bucket", "documents/test/lost-response");
                stored.add(candidate);
                return candidate;
            });

        // 1. 서버 처리가 끝난 첫 HTTP 응답을 시험 클라이언트에서 버려 결과 불명 상태를 만든다.
        int firstServerStatus = sendUpload().getResponse().getStatus();
        Counts afterFirst = counts();

        // 2. 같은 사용자·파일·메타데이터를 다시 보내고, 서버 상태와 사용자 관측값을 따로 기록한다.
        var retryResult = sendUpload();
        int retryServerStatus = retryResult.getResponse().getStatus();
        JsonNode retryBody = objectMapper.readTree(retryResult.getResponse().getContentAsString());
        Counts afterRetry = counts();
        writeLedger("response-lost", List.of(
            row("A1", "응답 폐기", firstServerStatus, afterFirst),
            row("A2", "201 수신", retryServerStatus, afterRetry)
        ));

        assertThat(firstServerStatus).isEqualTo(201);
        assertThat(retryServerStatus).isEqualTo(201);
        assertThat(retryBody.path("data").path("documentId").asLong()).isPositive();
        assertThat(afterFirst).isEqualTo(new Counts(1, 1, 1, 1));
        assertThat(afterRetry).isEqualTo(new Counts(2, 2, 2, 1));
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("저장소 기록 직후 실행이 중단되면 DB 미참조 후보가 남는다")
    void storageCompleted_thenProcessStopBoundaryLeavesOrphan() throws IOException {
        StoredFile candidate = new StoredFile(
            StorageProvider.GCS, "test-bucket", "documents/test/unreferenced-candidate"
        );
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                stored.add(candidate);
                return candidate;
            });
        // DB 서비스 진입 경계에서 Error를 던져 프로세스 종료 시 실행되지 않는 보상 경로를 모의한다.
        doThrow(new AssertionError("simulated abrupt stop"))
            .when(documentUploadService).upload(any());

        // 1. 저장소가 성공 응답을 준 뒤 DB 트랜잭션 전에 실행을 중단한다.
        assertThatThrownBy(() -> documentUploadFacade.upload(userId, request()))
            .isInstanceOf(AssertionError.class);

        // 2. 외부 저장소 후보와 DB 참조가 불일치하는지 대조한다.
        Counts afterStop = counts();
        writeLedger("storage-before-db-stop", List.of(row("A1", "응답 없음", 0, afterStop)));
        assertThat(stored).containsExactly(candidate);
        assertThat(afterStop).isEqualTo(new Counts(0, 0, 0, 0));
        verify(fileStorageService, never()).delete(candidate);
    }

    @Test
    @DisplayName("동일 요청을 동시에 전송하면 문서·Job은 둘이고 물리 파일만 재사용한다")
    void concurrentSameRequestCreatesTwoLogicalUploads() throws Exception {
        CyclicBarrier storageBarrier = new CyclicBarrier(2);
        AtomicInteger sequence = new AtomicInteger();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                storageBarrier.await(10, TimeUnit.SECONDS);
                StoredFile candidate = new StoredFile(
                    StorageProvider.LOCAL, "test-bucket", "documents/test/candidate-" + sequence.incrementAndGet()
                );
                stored.add(candidate);
                return candidate;
            });

        // 1. 두 클라이언트 요청을 같은 파일·제목으로 동시에 시작한다.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(this::sendUpload);
            Future<MvcResult> second = executor.submit(this::sendUpload);
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
            JsonNode firstData = objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("data");
            JsonNode secondData = objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("data");

            // 2. 요청별 논리 결과와 공유 FileObject를 분리해 기록한다.
            Counts afterBoth = counts();
            writeLedger("concurrent-retry", List.of(
                row("A1", "서비스 성공", 201, afterBoth),
                row("A2", "서비스 성공", 201, afterBoth)
            ));
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(firstData.path("documentId").asLong()).isNotEqualTo(secondData.path("documentId").asLong());
            assertThat(firstData.path("embeddingJobId").asLong()).isNotEqualTo(secondData.path("embeddingJobId").asLong());
            assertThat(firstData.path("fileObjectId").asLong()).isEqualTo(secondData.path("fileObjectId").asLong());
            assertThat(afterBoth).isEqualTo(new Counts(2, 2, 2, 1));
            assertThat(stored).hasSize(2);
            assertThat(deleted).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult sendUpload() throws Exception {
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
            "test-user", "test-credentials", List.of()
        );
        auth.setDetails(userId);
        return mockMvc.perform(multipart("/api/documents")
            .file(file())
            .param("title", title)
            .param("description", "baseline")
            .param("visibility", "PRIVATE")
            .with(csrf())
            .with(authentication(auth))).andReturn();
    }

    private DocumentUploadRequest request() {
        return new DocumentUploadRequest(file(), title, "baseline", VisibilityType.PRIVATE);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "sample.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private Counts counts() {
        int documents = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE title = ?", Integer.class, title
        );
        int versions = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM document_versions v JOIN documents d ON d.id = v.document_id WHERE d.title = ?
            """, Integer.class, title);
        int jobs = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM embedding_jobs j
            JOIN document_versions v ON v.id = j.document_version_id
            JOIN documents d ON d.id = v.document_id WHERE d.title = ?
            """, Integer.class, title);
        int fileObjects = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM file_objects WHERE file_hash = ?", Integer.class,
            fileHashService.calculateSha256(file())
        );
        return new Counts(documents, versions, jobs, fileObjects);
    }

    private String row(String attempt, String clientOutcome, int serverStatus, Counts counts) {
        return "%s,%s,%d,%d,%d,%d,%d,%d,%d".formatted(
            attempt, clientOutcome, serverStatus, counts.documents(), counts.versions(),
            counts.jobs(), counts.fileObjects(), stored.size(), deleted.size()
        );
    }

    private void writeLedger(String scenario, List<String> rows) throws IOException {
        // 로컬 시험의 합성 식별자와 집계만 저장하며 사용자·버킷·객체 키는 원장에 기록하지 않는다.
        Path directory = Path.of(System.getenv().getOrDefault(
            "UPLOAD_BASELINE_LEDGER_DIR", "build/test-results/upload-ambiguity-baseline"
        ));
        Files.createDirectories(directory);
        Path ledger = directory.resolve(RUN_ID + "-" + scenario + ".csv");
        String body = "attempt,client_outcome,server_status,documents,versions,jobs,file_objects,stored_candidates,deleted_candidates\n"
            + String.join("\n", rows) + "\n";
        Files.writeString(ledger, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** DB와 시험용 저장소 Port의 집계만 담아 요청 원장에 기록한다. */
    private record Counts(int documents, int versions, int jobs, int fileObjects) {
    }
}
