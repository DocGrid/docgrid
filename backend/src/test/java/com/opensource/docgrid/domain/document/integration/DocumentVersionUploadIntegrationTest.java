package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("문서 새 버전 업로드 통합 테스트")
class DocumentVersionUploadIntegrationTest {

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private DocumentVersionUploadFacade documentVersionUploadFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    private final List<Long> documentIds = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void setUp() {
        reset(fileStorageService);
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        for (Long documentId : documentIds) {
            jdbcTemplate.update("DELETE FROM version_upload_requests WHERE document_id = ?", documentId);
            List<Long> fileObjectIds = jdbcTemplate.queryForList(
                "SELECT file_object_id FROM document_versions WHERE document_id = ? AND file_object_id IS NOT NULL",
                Long.class,
                documentId
            );
            jdbcTemplate.update("DELETE FROM embedding_jobs WHERE document_version_id IN "
                + "(SELECT id FROM document_versions WHERE document_id = ?)", documentId);
            jdbcTemplate.update(
                "DELETE FROM sync_outbox_events WHERE aggregate_type = 'DOCUMENT_VERSION' "
                    + "AND aggregate_id IN (SELECT id FROM document_versions WHERE document_id = ?)",
                documentId
            );
            jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", documentId);
            jdbcTemplate.update("DELETE FROM document_versions WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            fileObjectIds.stream().distinct().forEach(fileObjectId -> jdbcTemplate.update(
                "DELETE FROM file_objects WHERE id = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM document_versions WHERE file_object_id = ?)",
                fileObjectId,
                fileObjectId
            ));
        }
    }

    @Test
    @DisplayName("같은 사용자·문서·키·파일의 재시도는 최초 버전과 Job을 그대로 반환한다")
    void upload_reusesOriginalVersionAndJob_whenSameKeyIsRetried() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("version-idempotency-base");
        reset(fileStorageService);
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));

        DocumentVersionUploadRequest request = versionRequest("version-idempotency-new", "updated.txt");
        DocumentVersionUploadResponse first = documentVersionUploadFacade.upload(
            userId, initial.documentId(), request, "version-attempt-1"
        );
        // Worker가 완료해 문서 상태가 바뀐 뒤에도 재시도 응답은 최초 201 결과를 유지해야 한다.
        jdbcTemplate.update("UPDATE document_versions SET status = 'INDEXED' WHERE id = ?", first.documentVersionId());
        jdbcTemplate.update("UPDATE embedding_jobs SET status = 'INDEXED' WHERE id = ?", first.embeddingJobId());
        jdbcTemplate.update("UPDATE documents SET status = 'INDEXED', current_version_id = ? WHERE id = ?",
            first.documentVersionId(), initial.documentId());
        DocumentVersionUploadResponse replay = documentVersionUploadFacade.upload(
            userId, initial.documentId(), request, "version-attempt-1"
        );

        assertThat(replay).isEqualTo(first);
        assertThat(countBy("SELECT COUNT(*) FROM document_versions WHERE document_id = ?", initial.documentId()))
            .isEqualTo(2);
        assertThat(countBy("""
            SELECT COUNT(*) FROM embedding_jobs job
            JOIN document_versions version ON version.id = job.document_version_id
            WHERE version.document_id = ?
            """, initial.documentId())).isEqualTo(2);
        assertThat(countBy("""
            SELECT COUNT(*) FROM sync_outbox_events event
            JOIN document_versions version ON version.id = event.aggregate_id
            WHERE event.aggregate_type = 'DOCUMENT_VERSION' AND version.document_id = ?
            """, initial.documentId())).isEqualTo(2);
        verify(fileStorageService, times(1))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 키에 다른 파일을 보내면 새 버전을 만들지 않고 충돌을 반환한다")
    void upload_rejectsDifferentFile_whenKeyIsReused() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("version-conflict-base");
        documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-conflict-A", "updated.txt"),
            "version-attempt-conflict"
        );

        assertThatThrownBy(() -> documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-conflict-B", "updated.txt"),
            "version-attempt-conflict"
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_UPLOAD_KEY_CONFLICT);
        assertThat(countBy("SELECT COUNT(*) FROM document_versions WHERE document_id = ?", initial.documentId()))
            .isEqualTo(2);
    }

    @Test
    @DisplayName("저장소 실패 뒤 같은 키로 재시도하면 처리권을 재획득해 버전 하나만 생성한다")
    void upload_retriesSameKey_afterConfirmedStorageFailure() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("version-recovery-base");
        reset(fileStorageService);
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willThrow(new DocGridException(ErrorCode.FILE_STORAGE_FAILED))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));

        DocumentVersionUploadRequest request = versionRequest("version-recovery-new", "updated.txt");
        assertThatThrownBy(() -> documentVersionUploadFacade.upload(
            userId, initial.documentId(), request, "version-attempt-recovery"
        )).isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_STORAGE_FAILED);

        DocumentVersionUploadResponse response = documentVersionUploadFacade.upload(
            userId, initial.documentId(), request, "version-attempt-recovery"
        );
        assertThat(response.versionNo()).isEqualTo(2);
        assertThat(countBy("SELECT COUNT(*) FROM document_versions WHERE document_id = ?", initial.documentId()))
            .isEqualTo(2);
        assertThat(countBy("""
            SELECT COUNT(*) FROM embedding_jobs job
            JOIN document_versions version ON version.id = job.document_version_id
            WHERE version.document_id = ?
            """, initial.documentId())).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 키의 동시 요청은 저장소에 함께 진입하지 않고 완료 뒤 같은 결과로 수렴한다")
    void upload_serializesSameKey_beforeStorage() throws Exception {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("version-concurrent-base");
        reset(fileStorageService);
        CountDownLatch firstAtStorage = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                firstAtStorage.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("첫 업로드 저장 대기 시간 초과");
                }
                return new StoredFile(StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID());
            });
        DocumentVersionUploadRequest request = versionRequest("version-concurrent-new", "updated.txt");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        DocumentVersionUploadResponse first;
        try {
            Future<DocumentVersionUploadResponse> pending = executor.submit(() -> documentVersionUploadFacade.upload(
                userId, initial.documentId(), request, "version-attempt-concurrent"
            ));
            assertThat(firstAtStorage.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> documentVersionUploadFacade.upload(
                userId, initial.documentId(), request, "version-attempt-concurrent"
            )).isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_UPLOAD_IN_PROGRESS);
            releaseFirst.countDown();
            first = pending.get(15, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(documentVersionUploadFacade.upload(
            userId, initial.documentId(), request, "version-attempt-concurrent"
        )).isEqualTo(first);
        verify(fileStorageService, times(1))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
        assertThat(countBy("SELECT COUNT(*) FROM document_versions WHERE document_id = ?", initial.documentId()))
            .isEqualTo(2);
    }

    @Test
    @DisplayName("길이가 같아도 내용이 다르면 v2를 만들고 current version은 유지한다")
    void upload_createsVersion_when_sameSizeButContentDiffers() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("AAAA");

        DocumentVersionUploadResponse response = documentVersionUploadFacade.upload(
            userId,
            initial.documentId(),
            versionRequest("BBBB", "changed.txt")
        );

        assertThat(response.versionNo()).isEqualTo(2);
        assertThat(response.currentVersionId()).isEqualTo(initial.documentVersionId());
        assertThat(response.documentStatus().name()).isEqualTo("INDEXED");
        assertThat(response.versionStatus().name()).isEqualTo("UPLOADED");
        assertThat(response.jobStatus().name()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT original_filename FROM document_versions WHERE id = ?",
            String.class,
            response.documentVersionId()
        )).isEqualTo("changed.txt");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT content_type FROM document_versions WHERE id = ?",
            String.class,
            response.documentVersionId()
        )).isEqualTo("text/plain");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_outbox_events "
                + "WHERE aggregate_type = 'DOCUMENT_VERSION' AND aggregate_id = ? "
                + "AND event_type = 'DOCUMENT_VERSION_CREATED' AND status = 'PENDING'",
            Integer.class,
            response.documentVersionId()
        )).isOne();
    }

    @Test
    @DisplayName("현재 버전과 같은 파일이면 저장소 업로드 없이 409 예외를 반환한다")
    void upload_rejectsSameCurrentFile_beforeStorage() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("same-content");
        reset(fileStorageService);

        assertThatThrownBy(() -> documentVersionUploadFacade.upload(
            userId,
            initial.documentId(),
            versionRequest("same-content", "renamed.txt")
        ))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_VERSION_SAME_CONTENT);

        verify(fileStorageService, times(0))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
            Integer.class,
            initial.documentId()
        )).isOne();
    }

    @Test
    @DisplayName("과거 버전 파일로 되돌리면 기존 FileObject를 재사용해 새 버전을 만든다")
    void upload_reusesHistoricalFileObject_when_revertingContent() {
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("version-A");
        DocumentVersionUploadResponse second = documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-B", "second.txt")
        );
        jdbcTemplate.update("UPDATE document_versions SET status = 'INDEXED' WHERE id = ?", second.documentVersionId());
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ?, status = 'INDEXED' WHERE id = ?",
            second.documentVersionId(),
            initial.documentId()
        );
        reset(fileStorageService);

        DocumentVersionUploadResponse reverted = documentVersionUploadFacade.upload(
            userId, initial.documentId(), versionRequest("version-A", "reverted.txt")
        );

        Long initialFileObjectId = jdbcTemplate.queryForObject(
            "SELECT file_object_id FROM document_versions WHERE id = ?",
            Long.class,
            initial.documentVersionId()
        );
        Long revertedFileObjectId = jdbcTemplate.queryForObject(
            "SELECT file_object_id FROM document_versions WHERE id = ?",
            Long.class,
            reverted.documentVersionId()
        );
        assertThat(reverted.versionNo()).isEqualTo(3);
        assertThat(revertedFileObjectId).isEqualTo(initialFileObjectId);
        verify(fileStorageService, times(0))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 문서에 새 버전을 동시에 올려도 살아 있는 Embedding Job은 하나만 남는다")
    void upload_createsSingleLiveJob_when_twoVersionsUploadedConcurrently() throws Exception {
        String suffix = UUID.randomUUID().toString();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
            ));
        DocumentUploadResponse initial = createIndexedDocument("concurrent-base-" + suffix);
        // v1은 인덱싱을 마친 상태이므로 이전 Job을 종료 상태로 두고 새 Job만 살아 있게 만든다.
        jdbcTemplate.update(
            "UPDATE embedding_jobs SET status = 'INDEXED' WHERE document_version_id = ?",
            initial.documentVersionId()
        );
        reset(fileStorageService);

        // 저장 단계는 쓰기 Transaction 직전이므로, 여기서 두 요청을 함께 붙잡아야 둘 다 Commit 전에
        // 같은 Document 행을 두고 실제로 경합한다. 호출 전에만 맞추면 순차 실행으로도 통과할 수 있다.
        CyclicBarrier storageBarrier = new CyclicBarrier(2);
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> {
                storageBarrier.await(10, TimeUnit.SECONDS);
                return new StoredFile(
                    StorageProvider.LOCAL, "test-bucket", "documents/test/" + UUID.randomUUID()
                );
            });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<UploadOutcome> outcomes;
        try {
            List<Future<UploadOutcome>> futures = List.of(
                executor.submit(() -> upload(initial.documentId(), "concurrent-A-" + suffix)),
                executor.submit(() -> upload(initial.documentId(), "concurrent-B-" + suffix))
            );
            outcomes = List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // Barrier가 풀렸다는 것은 두 요청이 모두 Commit 전 상태로 저장 단계에 함께 도달했다는 뜻이다.
        verify(fileStorageService, times(2))
            .store(any(InputStream.class), anyLong(), anyString(), anyString());
        // Document 행 잠금과 진행 중 Version 부분 Unique Index가 함께 두 번째 요청을 되돌린다.
        assertThat(outcomes).filteredOn(UploadOutcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
            .extracting(UploadOutcome::errorCode)
            .containsExactly(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS);
        // 되돌아간 요청은 이미 올린 후보 Object를 정리하고 어떤 행도 남기지 않는다.
        verify(fileStorageService, times(1)).delete(any(StoredFile.class));
        assertThat(countBy(
            "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
            initial.documentId()
        )).isEqualTo(2);
        assertThat(countBy("""
            SELECT COUNT(*)
            FROM document_versions
            WHERE document_id = ?
              AND status IN ('UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING')
            """, initial.documentId())).isOne();
        assertThat(countBy("""
            SELECT COUNT(*)
            FROM embedding_jobs job
            JOIN document_versions version ON version.id = job.document_version_id
            WHERE version.document_id = ?
              AND job.status IN ('PENDING', 'PROCESSING')
            """, initial.documentId())).isOne();
    }

    private UploadOutcome upload(Long documentId, String content) {
        try {
            documentVersionUploadFacade.upload(userId, documentId, versionRequest(content, content + ".txt"));
            return new UploadOutcome(true, null);
        } catch (DocGridException exception) {
            return new UploadOutcome(false, exception.getErrorCode());
        }
    }

    private Integer countBy(String sql, Long documentId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, documentId);
    }

    private DocumentUploadResponse createIndexedDocument(String content) {
        DocumentUploadRequest request = new DocumentUploadRequest(
            new MockMultipartFile("file", "initial.txt", "text/plain", content.getBytes()),
            "version-test-" + UUID.randomUUID(),
            "통합 테스트",
            VisibilityType.PRIVATE
        );
        DocumentUploadResponse response = documentUploadFacade.upload(userId, request);
        documentIds.add(response.documentId());
        jdbcTemplate.update("UPDATE document_versions SET status = 'INDEXED' WHERE id = ?", response.documentVersionId());
        jdbcTemplate.update("UPDATE documents SET status = 'INDEXED' WHERE id = ?", response.documentId());
        return response;
    }

    private DocumentVersionUploadRequest versionRequest(String content, String filename) {
        return new DocumentVersionUploadRequest(
            new MockMultipartFile("file", filename, "text/plain", content.getBytes())
        );
    }

    /**
     * 동시 새 버전 업로드 한 건의 성공 여부와 거부 사유 코드.
     */
    private record UploadOutcome(boolean succeeded, ErrorCode errorCode) {
    }
}
