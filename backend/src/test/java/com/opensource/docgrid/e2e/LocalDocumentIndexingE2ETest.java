package com.opensource.docgrid.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerExecutionLifecycleManager;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerJobPollingScheduler;
import com.opensource.docgrid.domain.worker.lifecycle.WorkerLifecycleManager;
import com.opensource.docgrid.e2e.LocalE2eApiClient.UploadedDocument;
import com.opensource.docgrid.e2e.LocalE2eDocumentFactory.DocumentPayload;

import io.minio.MinioClient;

/**
 * 실제 PostgreSQL 17·MinIO·BGE-M3에서 PDF·DOCX·HWP·HWPX 업로드부터 pgvector 검색까지 제품 경계를 관통한다.
 *
 * <p>Mock Bean 없이 실제 HTTP, 자동 Worker, Parser, Batch 임베딩과 완료 Transaction을 사용하며 Test
 * 전용 Schema와 Bucket만 생성·정리한다.
 */
@Tag("local-e2e")
@ActiveProfiles({"test", "minio-integration"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("실제 문서 인덱싱 로컬 전체 관통 E2E")
class LocalDocumentIndexingE2ETest {

    private static final String TEST_SCHEMA = "docgrid_local_document_indexing_e2e";
    private static final String TEST_BUCKET = "docgrid-e2e-"
        + UUID.randomUUID().toString().replace("-", "");
    private static final Duration PIPELINE_TIMEOUT = Duration.ofMinutes(4);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MinioClient minioClient;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private VectorSearchQueryService vectorSearchQueryService;
    @Autowired private WorkerLifecycleManager workerLifecycleManager;
    @Autowired private WorkerJobPollingScheduler pollingScheduler;
    @Autowired private WorkerExecutionLifecycleManager executionLifecycleManager;

    private LocalE2eApiClient apiClient;
    private LocalE2eMinioBucket minioBucket;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-local-document-indexing-e2e-secret-key-2026");
        registry.add("storage.bucket", () -> TEST_BUCKET);
        registry.add("indexing.worker.enabled", () -> "true");
        registry.add("indexing.worker.name", () -> "local-document-indexing-e2e-worker");
        registry.add("indexing.worker.polling-interval", () -> "100ms");
        registry.add("indexing.worker.heartbeat-interval", () -> "1s");
        registry.add("indexing.worker.dead-threshold", () -> "2m");
        registry.add("indexing.worker.max-concurrency", () -> "1");
        registry.add("indexing.worker.lease-duration", () -> "30s");
        registry.add("indexing.worker.lease-renewal-interval", () -> "5s");
        registry.add("indexing.worker.lease-recovery-interval", () -> "10m");
        registry.add("indexing.worker.shutdown-grace-period", () -> "10s");
    }

    @BeforeAll
    void setUpInfrastructure() throws Exception {
        apiClient = new LocalE2eApiClient(restTemplate);
        minioBucket = new LocalE2eMinioBucket(minioClient, TEST_BUCKET);
        minioBucket.create();
        awaitCondition(
            "Worker가 Application Ready 뒤 등록되지 않았습니다.",
            () -> workerLifecycleManager.getWorkerId().isPresent()
        );
    }

    @AfterAll
    void cleanUpInfrastructure() throws Exception {
        // 1. Scheduler와 실행 Thread를 먼저 닫아 Schema 정리 뒤 DB 접근이 재개되지 않게 한다.
        pollingScheduler.stopPolling();
        executionLifecycleManager.shutdown();
        workerLifecycleManager.stopWorker();

        // 2. 이 Test가 만든 Bucket과 Schema만 제거해 기존 로컬 개발 Data를 보존한다.
        minioBucket.close();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @Timeout(300)
    @DisplayName("PDF·DOCX 업로드가 자동 Worker를 거쳐 1024차원 Vector와 의미 검색으로 완료된다")
    void upload_indexesDocumentsAndSearchesRelatedContent() throws Exception {
        long startedAt = System.nanoTime();
        String accessToken = apiClient.loginAdmin();
        DocumentPayload pdf = LocalE2eDocumentFactory.pdf(
            "pgvector-guide.pdf",
            "PostgreSQL Vector Index Guide",
            "PostgreSQL pgvector HNSW index accelerates cosine similarity search for document embeddings.",
            "Worker lease renewal prevents duplicate indexing while vector data is stored atomically."
        );
        DocumentPayload docx = LocalE2eDocumentFactory.docx(
            "recipe-handbook.docx",
            "Culinary Recipe Handbook",
            "Bread Baking",
            "Flour water yeast and salt are combined before dough fermentation and oven baking."
        );

        // 1. 실제 ADMIN JWT와 Multipart HTTP로 서로 다른 문서 형식을 접수한다.
        UploadedDocument pdfUpload = apiClient.upload(accessToken, pdf);
        UploadedDocument docxUpload = apiClient.upload(accessToken, docx);

        // 2. DB FileObject 위치와 실제 MinIO Object의 크기·Content-Type을 교차 검증한다.
        assertStoredObject(pdfUpload, pdf);
        assertStoredObject(docxUpload, docx);

        // 3. 자동 Worker가 두 Job을 완료할 때까지 제한 시간 안에서 실제 상태를 Polling한다.
        awaitJobIndexed(pdfUpload.embeddingJobId());
        awaitJobIndexed(docxUpload.embeddingJobId());

        // 4. 완료·Chunk·Vector·Metadata와 Event 순서 불변식을 문서별로 검증한다.
        assertIndexedInvariants(pdfUpload, true);
        assertIndexedInvariants(docxUpload, false);

        // 5. 실제 단건 Query Embedding과 pgvector 코사인 검색이 질의별로 관련 문서만 반환한다.
        assertSemanticSearchAppliesRelevanceGuardrail(pdfUpload, docxUpload);

        // 6. 같은 ADMIN JWT로 실제 관리자 조회와 OpenAPI 노출·민감정보 제거 계약을 검증한다.
        assertAdminHttpContracts(accessToken, pdfUpload);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(PIPELINE_TIMEOUT);
    }

    @Test
    @Timeout(300)
    @DisplayName("HWP·HWPX 업로드가 표 셀을 포함한 Chunk와 Vector 검색까지 완료된다")
    void upload_indexesHancomDocumentsAndSearchesTableText() throws Exception {
        String accessToken = apiClient.loginAdmin();
        DocumentPayload hwp = hancomPayload("table.hwp", MediaType.APPLICATION_OCTET_STREAM);
        DocumentPayload hwpx = hancomPayload("table.hwpx", MediaType.parseMediaType("application/hwp+zip"));

        // 1. 실제 Multipart 업로드가 HWP·HWPX 구조와 MIME을 통과해 원본 객체·Job을 만든다.
        UploadedDocument hwpUpload = apiClient.upload(accessToken, hwp);
        UploadedDocument hwpxUpload = apiClient.upload(accessToken, hwpx);
        assertStoredObject(hwpUpload, hwp);
        assertStoredObject(hwpxUpload, hwpx);

        // 2. 자동 Worker의 본문·표 추출과 임베딩 완료를 DB 결과로 대조한다.
        awaitJobIndexed(hwpUpload.embeddingJobId());
        awaitJobIndexed(hwpxUpload.embeddingJobId());
        assertHancomChunks(hwpUpload, "ABC");
        assertHancomChunks(hwpxUpload, "개똥이");

        // 3. 실제 BGE-M3 Query Embedding과 pgvector 검색이 각 문서의 표 텍스트를 찾는지 확인한다.
        assertSearchesTableChunk(hwpUpload, "ABC");
        assertSearchesTableChunk(hwpxUpload, "개똥이");
    }

    private DocumentPayload hancomPayload(String fileName, MediaType mediaType) throws IOException {
        try (var input = getClass().getResourceAsStream("/hancom/" + fileName)) {
            return new DocumentPayload(fileName, mediaType, fileName + " 검색 시험", input.readAllBytes());
        }
    }

    private void assertHancomChunks(UploadedDocument upload, String expectedText) {
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", upload.documentVersionId()))
            .isEqualTo("INDEXED");
        assertThat(count("SELECT COUNT(*) FROM embeddings WHERE document_version_id = ? AND status = 'ACTIVE'",
            upload.documentVersionId())).isPositive();
        List<String> texts = jdbcTemplate.queryForList(
            "SELECT chunk_text FROM document_chunks WHERE document_version_id = ? ORDER BY chunk_index",
            String.class, upload.documentVersionId()
        );
        assertThat(texts).anySatisfy(text -> assertThat(text).contains(expectedText));
    }

    private void assertSearchesTableChunk(UploadedDocument upload, String tableText) {
        String tableChunk = queryString(
            "SELECT chunk_text FROM document_chunks WHERE document_version_id = ? "
                + "AND chunk_text LIKE ? ORDER BY chunk_index LIMIT 1",
            upload.documentVersionId(), "%" + tableText + "%"
        );
        assertThat(tableChunk).contains(tableText);
        Long modelId = queryLong("SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE");
        assertThat(searchByQuery(tableChunk, modelId, List.of(upload.documentId())))
            .anySatisfy(candidate -> assertThat(candidate.documentId()).isEqualTo(upload.documentId()));
    }

    private void assertStoredObject(UploadedDocument upload, DocumentPayload payload) throws Exception {
        StoredObject storedObject = jdbcTemplate.queryForObject(
            "SELECT bucket_name, object_key, content_type FROM file_objects WHERE id = ?",
            (resultSet, rowNum) -> new StoredObject(
                resultSet.getString("bucket_name"),
                resultSet.getString("object_key"),
                resultSet.getString("content_type")
            ),
            upload.fileObjectId()
        );
        assertThat(storedObject).isNotNull();
        assertThat(storedObject.bucketName()).isEqualTo(TEST_BUCKET);
        assertThat(storedObject.contentType()).isEqualTo(payload.mediaType().toString());
        assertThat(minioBucket.stat(storedObject.objectKey()).size()).isEqualTo(payload.content().length);
    }

    private void awaitJobIndexed(Long jobId) throws InterruptedException {
        awaitCondition(
            "Job이 INDEXED로 완료되지 않았습니다. " + jobSnapshot(jobId),
            () -> "INDEXED".equals(queryString("SELECT status FROM embedding_jobs WHERE id = ?", jobId))
        );
    }

    private void assertIndexedInvariants(UploadedDocument upload, boolean pdf) {
        assertThat(queryString("SELECT status FROM documents WHERE id = ?", upload.documentId()))
            .isEqualTo("INDEXED");
        assertThat(queryLong("SELECT current_version_id FROM documents WHERE id = ?", upload.documentId()))
            .isEqualTo(upload.documentVersionId());
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", upload.documentVersionId()))
            .isEqualTo("INDEXED");
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", upload.embeddingJobId()))
            .isEqualTo("INDEXED");
        assertThat(count("SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ? AND status = 'SUCCESS'",
            upload.embeddingJobId())).isOne();

        int chunkCount = count(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            upload.documentVersionId()
        );
        int embeddingCount = count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        );
        assertThat(chunkCount).isPositive();
        assertThat(embeddingCount).isEqualTo(chunkCount);
        assertThat(count(
            "SELECT COUNT(DISTINCT chunk_id) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isEqualTo(embeddingCount);
        assertThat(queryInteger(
            "SELECT MIN(vector_dims(vector)) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isEqualTo(1024);
        assertThat(queryInteger(
            "SELECT MAX(vector_dims(vector)) FROM embeddings WHERE document_version_id = ?",
            upload.documentVersionId()
        )).isEqualTo(1024);
        assertThat(count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ? AND status = 'ACTIVE'",
            upload.documentVersionId()
        )).isEqualTo(embeddingCount);

        if (pdf) {
            assertThat(count(
                "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ? AND page_no IS NOT NULL",
                upload.documentVersionId()
            )).isEqualTo(chunkCount);
        } else {
            assertThat(count(
                "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ? AND section_title = 'Bread Baking'",
                upload.documentVersionId()
            )).isPositive();
        }
        assertEventOrder(upload.embeddingJobId());
    }

    private void assertEventOrder(Long jobId) {
        List<String> events = jdbcTemplate.queryForList(
            "SELECT event_type FROM indexing_events WHERE embedding_job_id = ? ORDER BY id",
            String.class,
            jobId
        );
        assertThat(events).containsSubsequence(
            "LOCKED",
            "PARSE_STARTED",
            "CHUNKED",
            "EMBEDDING_STARTED",
            "INDEXED"
        );
    }

    /**
     * 질의와 관련된 문서만 검색되고, 관련 없는 문서는 최소 유사도에서 제외되는지 확인한다.
     *
     * <p>두 질의를 함께 검증한다. 한 질의만 보면 문서가 빠진 이유가 관련성 판정 때문인지
     * 인덱싱 누락 때문인지 구분할 수 없기 때문이다.
     */
    private void assertSemanticSearchAppliesRelevanceGuardrail(
        UploadedDocument pdfUpload,
        UploadedDocument docxUpload
    ) {
        Long modelId = queryLong(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE"
        );
        List<Long> permittedIds = List.of(pdfUpload.documentId(), docxUpload.documentId());

        // 1. pgvector 질의에는 관련 PDF만 남고 무관한 제빵 DOCX는 최소 유사도에서 제외된다.
        List<VectorSearchCandidate> vectorQueryHits = searchByQuery(
            "How does pgvector HNSW improve cosine similarity search for embeddings?",
            modelId,
            permittedIds
        );
        assertThat(vectorQueryHits).isNotEmpty();
        assertThat(vectorQueryHits.get(0).documentId()).isEqualTo(pdfUpload.documentId());
        assertThat(vectorQueryHits)
            .allMatch(candidate -> candidate.documentId().equals(pdfUpload.documentId()));

        // 2. 같은 DOCX도 관련 질의에는 검색된다. 1의 제외가 인덱싱 누락이 아니라 관련성 판정임을 확인한다.
        List<VectorSearchCandidate> bakingQueryHits = searchByQuery(
            "How is bread dough fermented with yeast before baking?",
            modelId,
            permittedIds
        );
        assertThat(bakingQueryHits).isNotEmpty();
        assertThat(bakingQueryHits.get(0).documentId()).isEqualTo(docxUpload.documentId());
    }

    private List<VectorSearchCandidate> searchByQuery(String query, Long modelId, List<Long> permittedIds) {
        float[] queryVector = embeddingClient.embed(query);
        assertThat(queryVector).hasSize(1024);
        for (float value : queryVector) {
            assertThat(Float.isFinite(value)).isTrue();
        }
        return vectorSearchQueryService.search(queryVector, modelId, permittedIds, 10);
    }

    private void assertAdminHttpContracts(String accessToken, UploadedDocument upload) {
        List<String> paths = List.of(
            "/admin/indexing-jobs?documentId=" + upload.documentId() + "&page=0&size=20",
            "/admin/indexing-jobs/" + upload.embeddingJobId(),
            "/admin/indexing-jobs/" + upload.embeddingJobId() + "/attempts?page=0&size=20",
            "/admin/indexing-jobs/" + upload.embeddingJobId() + "/events?page=0&size=20"
        );
        for (String path : paths) {
            ResponseEntity<JsonNode> response = apiClient.get(accessToken, path);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            String json = response.getBody().toString();
            assertThat(json)
                .doesNotContain("claimToken")
                .doesNotContain("errorMessage")
                .doesNotContain("metadataJson");
        }

        ResponseEntity<JsonNode> openApi = apiClient.get("/v3/api-docs");
        assertThat(openApi.getStatusCode().value()).isEqualTo(200);
        assertThat(openApi.getBody()).isNotNull();
        String pathsJson = openApi.getBody().path("paths").toString();
        assertThat(pathsJson)
            .contains("/admin/indexing-jobs")
            .contains("/admin/indexing-jobs/{jobId}")
            .contains("/admin/indexing-jobs/{jobId}/attempts")
            .contains("/admin/indexing-jobs/{jobId}/events");
    }

    private void awaitCondition(String failureMessage, CheckedCondition condition) throws InterruptedException {
        long deadline = System.nanoTime() + PIPELINE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(failureMessage);
    }

    private String jobSnapshot(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT status || ',retry=' || retry_count || ',error=' || COALESCE(error_code, 'none') "
                + "FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        );
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private Integer queryInteger(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private Long queryLong(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String queryString(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, String.class, arguments);
    }

    /** Polling 중 확인할 실제 외부·DB 조건을 예외 전달 가능 형태로 표현한다. */
    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate();
    }

    /** DB의 MinIO 위치·Content-Type Snapshot을 Fixture 확인에 필요한 값으로 제한한다. */
    private record StoredObject(String bucketName, String objectKey, String contentType) {
    }
}
