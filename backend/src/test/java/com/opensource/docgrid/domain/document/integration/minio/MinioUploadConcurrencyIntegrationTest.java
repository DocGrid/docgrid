package com.opensource.docgrid.domain.document.integration.minio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.domain.document.service.FileHashService;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.MinioStorageService;
import com.opensource.docgrid.domain.document.storage.StorageObjectMetadata;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag("integration")
@Tag("minio-integration")
@SpringBootTest
@ActiveProfiles({"test", "minio-integration"})
@Import(MinioUploadConcurrencyIntegrationTest.MinioTestConfig.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("실제 MinIO 업로드 동시성 통합 테스트")
class MinioUploadConcurrencyIntegrationTest {

    private static final String TEST_BUCKET = "docgrid-pr22-"
        + UUID.randomUUID().toString().replace("-", "");
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long BARRIER_TIMEOUT_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 20;

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private DocumentVersionUploadFacade documentVersionUploadFacade;
    @Autowired private FileHashService fileHashService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private BarrierControl barrierControl;
    @Autowired private BarrierFileStorageService storageService;

    private final Queue<Long> documentIds = new ConcurrentLinkedQueue<>();
    private Long userId;

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.bucket", () -> TEST_BUCKET);
    }

    @BeforeEach
    void setUp() {
        barrierControl.disarm();
        storageService.clearObservations();
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        barrierControl.disarm();
        cleanupDatabase();
        cleanupBucket();
        storageService.clearObservations();
    }

    @Test
    @DisplayName("동일 파일로 새 문서를 동시에 만들면 패자 후보를 삭제하고 실제 Object 하나만 남긴다")
    void uploadNewDocuments_keepsSingleMinioObject_when_sameFileRaces() throws Exception {
        String runId = UUID.randomUUID().toString();
        String content = "same-new-document-content-" + runId;
        DocumentUploadRequest firstRequest = newDocumentRequest(content, "동시 신규 문서 A " + runId);
        DocumentUploadRequest secondRequest = newDocumentRequest(content, "동시 신규 문서 B " + runId);
        String fileHash = fileHashService.calculateSha256(firstRequest.file());
        long fileSize = firstRequest.file().getSize();

        barrierControl.arm(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        DocumentUploadResponse firstResponse;
        DocumentUploadResponse secondResponse;
        try {
            Future<DocumentUploadResponse> first = executor.submit(
                () -> uploadAndTrack(firstRequest)
            );
            Future<DocumentUploadResponse> second = executor.submit(
                () -> uploadAndTrack(secondRequest)
            );
            firstResponse = first.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondResponse = second.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        List<StoredFile> storedCandidates = storageService.storedFiles();
        List<StoredFile> deletedCandidates = storageService.deletedFiles();
        List<String> remainingObjectKeys = listObjectKeys();
        String databaseObjectKey = findObjectKey(fileHash, fileSize);

        assertThat(firstResponse.documentId()).isNotEqualTo(secondResponse.documentId());
        assertThat(firstResponse.documentVersionId()).isNotEqualTo(secondResponse.documentVersionId());
        assertThat(firstResponse.embeddingJobId()).isNotEqualTo(secondResponse.embeddingJobId());
        assertThat(firstResponse.fileObjectId()).isEqualTo(secondResponse.fileObjectId());
        assertThat(countFileObjects(fileHash, fileSize)).isOne();
        assertThat(countDocuments(firstResponse.documentId(), secondResponse.documentId())).isEqualTo(2);
        assertThat(countVersions(firstResponse.documentId(), secondResponse.documentId())).isEqualTo(2);
        assertThat(countJobs(firstResponse.documentId(), secondResponse.documentId())).isEqualTo(2);
        assertThat(storedCandidates).hasSize(2);
        assertThat(storedCandidates.get(0).objectKey()).isNotEqualTo(storedCandidates.get(1).objectKey());
        assertThat(deletedCandidates).hasSize(1);
        assertThat(remainingObjectKeys).containsExactly(databaseObjectKey);
        assertThat(deletedCandidates.get(0).objectKey()).isNotEqualTo(databaseObjectKey);
        assertObjectMatches(databaseObjectKey, fileHash, fileSize, "text/plain");

        log.info("PR 2.2 신규 문서 경합 결과: documents=[{}, {}], versions=[{}, {}], fileObject={}, "
                + "storedCandidates={}, deletedCandidate={}, remainingObject={}",
            firstResponse.documentId(), secondResponse.documentId(),
            firstResponse.documentVersionId(), secondResponse.documentVersionId(),
            firstResponse.fileObjectId(), objectKeys(storedCandidates),
            deletedCandidates.get(0).objectKey(), databaseObjectKey);
    }

    @Test
    @DisplayName("동일 문서에 같은 수정 파일을 동시에 올리면 하나만 성공하고 실패 후보를 실제 삭제한다")
    void uploadVersions_createsSingleVersionAndDeletesFailedCandidate_when_sameFileRaces() throws Exception {
        String runId = UUID.randomUUID().toString();
        DocumentUploadResponse initial = documentUploadFacade.upload(
            userId, newDocumentRequest("initial-content-" + runId, "버전 경합 문서 " + runId)
        );
        documentIds.add(initial.documentId());
        markInitialVersionIndexed(initial);
        String initialObjectKey = findObjectKeyById(initial.fileObjectId());

        storageService.clearObservations();
        String changedContent = "changed-content-" + runId;
        DocumentVersionUploadRequest firstRequest = versionRequest(changedContent, "changed-a.txt");
        DocumentVersionUploadRequest secondRequest = versionRequest(changedContent, "changed-b.txt");
        String fileHash = fileHashService.calculateSha256(firstRequest.file());
        long fileSize = firstRequest.file().getSize();

        barrierControl.arm(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<DocumentVersionUploadResponse> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<DocumentVersionUploadResponse>> futures = List.of(
                executor.submit(() -> documentVersionUploadFacade.upload(userId, initial.documentId(), firstRequest)),
                executor.submit(() -> documentVersionUploadFacade.upload(userId, initial.documentId(), secondRequest))
            );
            for (Future<DocumentVersionUploadResponse> future : futures) {
                collectResult(future, successes, failures);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(DocGridException.class);
        assertThat(((DocGridException) failures.get(0)).getErrorCode())
            .isEqualTo(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS);

        DocumentVersionUploadResponse success = successes.get(0);
        List<StoredFile> storedCandidates = storageService.storedFiles();
        List<StoredFile> deletedCandidates = storageService.deletedFiles();
        List<String> remainingObjectKeys = listObjectKeys();
        String changedObjectKey = findObjectKey(fileHash, fileSize);

        assertThat(success.versionNo()).isEqualTo(2);
        assertThat(success.currentVersionId()).isEqualTo(initial.documentVersionId());
        assertThat(countVersions(initial.documentId())).isEqualTo(2);
        assertThat(countJobs(initial.documentId())).isEqualTo(2);
        assertThat(countFileObjects(fileHash, fileSize)).isOne();
        assertThat(countInProgressVersions(initial.documentId())).isOne();
        assertThat(storedCandidates).hasSize(2);
        assertThat(deletedCandidates).hasSize(1);
        assertThat(remainingObjectKeys).containsExactlyInAnyOrder(initialObjectKey, changedObjectKey);
        assertThat(deletedCandidates.get(0).objectKey()).isNotIn(initialObjectKey, changedObjectKey);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_version_id FROM documents WHERE id = ?", Long.class, initial.documentId()
        )).isEqualTo(initial.documentVersionId());
        assertObjectMatches(changedObjectKey, fileHash, fileSize, "text/plain");

        log.info("PR 2.2 버전 경합 결과: document={}, successVersion={}, failedCode={}, currentVersion={}, "
                + "storedCandidates={}, deletedCandidate={}, remainingObjects={}",
            initial.documentId(), success.documentVersionId(), ErrorCode.DOCUMENT_VERSION_IN_PROGRESS,
            success.currentVersionId(), objectKeys(storedCandidates),
            deletedCandidates.get(0).objectKey(), remainingObjectKeys);
    }

    private void collectResult(
        Future<DocumentVersionUploadResponse> future,
        List<DocumentVersionUploadResponse> successes,
        List<Throwable> failures
    ) throws Exception {
        try {
            successes.add(future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
            failures.add(rootCause(exception));
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void markInitialVersionIndexed(DocumentUploadResponse response) {
        jdbcTemplate.update(
            "UPDATE document_versions SET status = 'INDEXED', indexed_at = CURRENT_TIMESTAMP WHERE id = ?",
            response.documentVersionId()
        );
        jdbcTemplate.update(
            "UPDATE embedding_jobs SET status = 'INDEXED', completed_at = CURRENT_TIMESTAMP WHERE id = ?",
            response.embeddingJobId()
        );
        jdbcTemplate.update(
            "UPDATE documents SET status = 'INDEXED', current_version_id = ? WHERE id = ?",
            response.documentVersionId(), response.documentId()
        );
    }

    private DocumentUploadRequest newDocumentRequest(String content, String title) {
        return new DocumentUploadRequest(
            new MockMultipartFile("file", "sample.txt", "text/plain", content.getBytes()),
            title,
            "실제 MinIO 동시성 테스트",
            VisibilityType.PRIVATE
        );
    }

    private DocumentUploadResponse uploadAndTrack(DocumentUploadRequest request) {
        DocumentUploadResponse response = documentUploadFacade.upload(userId, request);
        documentIds.add(response.documentId());
        return response;
    }

    private DocumentVersionUploadRequest versionRequest(String content, String filename) {
        return new DocumentVersionUploadRequest(
            new MockMultipartFile("file", filename, "text/plain", content.getBytes())
        );
    }

    private int countFileObjects(String fileHash, long fileSize) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM file_objects WHERE file_hash = ? AND file_size = ?",
            Integer.class, fileHash, fileSize
        );
    }

    private int countDocuments(Long... ids) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM documents WHERE id IN (" + placeholders(ids.length) + ")",
            Integer.class, (Object[]) ids
        );
    }

    private int countVersions(Long... documentIds) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_versions WHERE document_id IN ("
                + placeholders(documentIds.length) + ")",
            Integer.class, (Object[]) documentIds
        );
    }

    private int countJobs(Long... documentIds) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_jobs WHERE document_version_id IN "
                + "(SELECT id FROM document_versions WHERE document_id IN ("
                + placeholders(documentIds.length) + "))",
            Integer.class, (Object[]) documentIds
        );
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private int countInProgressVersions(Long documentId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_versions WHERE document_id = ? "
                + "AND status IN ('UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING')",
            Integer.class, documentId
        );
    }

    private String findObjectKey(String fileHash, long fileSize) {
        return jdbcTemplate.queryForObject(
            "SELECT object_key FROM file_objects WHERE file_hash = ? AND file_size = ?",
            String.class, fileHash, fileSize
        );
    }

    private String findObjectKeyById(Long fileObjectId) {
        return jdbcTemplate.queryForObject(
            "SELECT object_key FROM file_objects WHERE id = ?", String.class, fileObjectId
        );
    }

    private List<String> listObjectKeys() throws Exception {
        List<String> objectKeys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
            ListObjectsArgs.builder().bucket(TEST_BUCKET).recursive(true).build()
        );
        for (Result<Item> result : results) {
            objectKeys.add(result.get().objectName());
        }
        return objectKeys;
    }

    private void assertObjectMatches(
        String objectKey,
        String expectedHash,
        long expectedSize,
        String expectedContentType
    ) throws Exception {
        StatObjectResponse stat = minioClient.statObject(
            StatObjectArgs.builder().bucket(TEST_BUCKET).object(objectKey).build()
        );
        assertThat(stat.size()).isEqualTo(expectedSize);
        assertThat(stat.contentType()).isEqualTo(expectedContentType);
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder().bucket(TEST_BUCKET).object(objectKey).build()
        )) {
            assertThat(hex(MessageDigest.getInstance("SHA-256").digest(inputStream.readAllBytes())))
                .isEqualTo(expectedHash);
        }
    }

    private String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private List<String> objectKeys(List<StoredFile> storedFiles) {
        return storedFiles.stream().map(StoredFile::objectKey).toList();
    }

    private void cleanupDatabase() {
        for (Long documentId : documentIds.stream().distinct().toList()) {
            jdbcTemplate.update(
                "DELETE FROM embedding_jobs WHERE document_version_id IN "
                    + "(SELECT id FROM document_versions WHERE document_id = ?)",
                documentId
            );
            jdbcTemplate.update("UPDATE documents SET current_version_id = NULL WHERE id = ?", documentId);
            jdbcTemplate.update("DELETE FROM document_versions WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
        }
        jdbcTemplate.update(
            "DELETE FROM file_objects WHERE bucket_name = ? AND NOT EXISTS "
                + "(SELECT 1 FROM document_versions WHERE file_object_id = file_objects.id)",
            TEST_BUCKET
        );
        documentIds.clear();
    }

    private void cleanupBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(TEST_BUCKET).build())) {
            return;
        }
        List<String> objectKeys = listObjectKeys();
        for (String objectKey : objectKeys) {
            minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(TEST_BUCKET).object(objectKey).build()
            );
        }
        minioClient.removeBucket(RemoveBucketArgs.builder().bucket(TEST_BUCKET).build());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MinioTestConfig {

        @Bean
        BarrierControl barrierControl() {
            return new BarrierControl();
        }

        @Bean
        @Primary
        BarrierFileStorageService barrierFileStorageService(
            MinioStorageService minioStorageService,
            BarrierControl barrierControl
        ) {
            return new BarrierFileStorageService(minioStorageService, barrierControl);
        }
    }

    static class BarrierControl {

        private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();

        void arm(int parties) {
            barrier.set(new CyclicBarrier(parties));
        }

        void awaitAfterStore() {
            CyclicBarrier current = barrier.get();
            if (current == null) {
                return;
            }
            try {
                current.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("실제 MinIO 저장 후 Barrier 대기에 실패했습니다.", exception);
            }
        }

        void disarm() {
            CyclicBarrier current = barrier.getAndSet(null);
            if (current != null) {
                current.reset();
            }
        }
    }

    @RequiredArgsConstructor
    static class BarrierFileStorageService implements FileStorageService {

        private final MinioStorageService delegate;
        private final BarrierControl barrierControl;
        private final Queue<StoredFile> storedFiles = new ConcurrentLinkedQueue<>();
        private final Queue<StoredFile> deletedFiles = new ConcurrentLinkedQueue<>();

        @Override
        public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
            StoredFile storedFile = delegate.store(inputStream, fileSize, contentType, objectKey);
            storedFiles.add(storedFile);
            barrierControl.awaitAfterStore();
            return storedFile;
        }

        @Override
        public byte[] read(StoredFile storedFile) {
            return delegate.read(storedFile);
        }

        @Override
        public Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize) {
            return delegate.streamObjects(prefix, pageSize);
        }

        @Override
        public void delete(StoredFile storedFile) {
            delegate.delete(storedFile);
            deletedFiles.add(storedFile);
        }

        List<StoredFile> storedFiles() {
            return List.copyOf(storedFiles);
        }

        List<StoredFile> deletedFiles() {
            return List.copyOf(deletedFiles);
        }

        void clearObservations() {
            storedFiles.clear();
            deletedFiles.clear();
        }
    }
}
