package com.opensource.docgrid.domain.document.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.DocumentParsingService;
import com.opensource.docgrid.domain.document.service.command.DocumentChunkTransactionService.ChunkResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StorageObjectMetadata;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentChunksRequest;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 실제 OpenSQL에서 문서 Chunk 저장의 상태·이벤트·멱등성과 동시 Transaction 수렴을 검증한다.
 *
 * <p>격리 Schema와 Transaction 밖 Storage Stub을 사용해 Chunk Row, Version 상태와 이벤트가
 * 원자적으로 저장되고 같은 실행의 순차·동시 재호출이 하나의 Chunk Set으로 수렴하는지 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@Import(DocumentChunkingIntegrationTest.ChunkStorageTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Document Chunking OpenSQL 통합 테스트")
class DocumentChunkingIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_document_chunking_test";
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DocumentParsingService documentParsingService;
    @Autowired private InMemoryChunkFileStorage fileStorage;

    @DynamicPropertySource
    static void configureChunking(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("document.chunking.chunk-size", () -> 4);
        registry.add("document.chunking.overlap", () -> 1);
        registry.add("jwt.secret", () -> "docgrid-chunk-integration-test-secret-key-2026");
    }

    @BeforeEach
    void resetState() {
        fileStorage.reset();
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                indexing_events,
                document_chunks,
                embedding_job_attempts,
                embedding_jobs,
                document_versions,
                documents,
                file_objects,
                worker_nodes
            RESTART IDENTITY CASCADE
            """);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("TXT 원본을 결정적 Chunk와 CHUNKED 상태 및 이벤트로 원자 저장하고 재생한다")
    void createChunks_persistsDeterministicSetAndReplays() {
        ExecutionContext context = insertExecution("abcdefgh");
        CreateDocumentChunksRequest request = new CreateDocumentChunksRequest(context.workerId(), CLAIM_TOKEN);

        ChunkResult created = documentParsingService.createChunks(context.jobId(), context.attemptId(), request);
        ChunkResult replayed = documentParsingService.createChunks(context.jobId(), context.attemptId(), request);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.response()).isEqualTo(created.response());
        assertThat(created.response().chunkCount()).isEqualTo(3);
        assertThat(created.response().versionStatus()).isEqualTo(DocumentVersionStatus.CHUNKED);

        assertThat(jdbcTemplate.queryForList("""
            SELECT chunk_index, chunk_text, token_count, char_start, char_end
            FROM document_chunks
            WHERE document_version_id = ?
            ORDER BY chunk_index
            """, context.versionId()))
            .containsExactly(
                Map.of("chunk_index", 0, "chunk_text", "abcd", "token_count", 1, "char_start", 0, "char_end", 4),
                Map.of("chunk_index", 1, "chunk_text", "defg", "token_count", 1, "char_start", 3, "char_end", 7),
                Map.of("chunk_index", 2, "chunk_text", "gh", "token_count", 1, "char_start", 6, "char_end", 8)
            );
        assertThat(jdbcTemplate.queryForList("""
            SELECT content_hash
            FROM document_chunks
            WHERE document_version_id = ?
            """, String.class, context.versionId()))
            .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", context.versionId()))
            .isEqualTo("CHUNKED");
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PROCESSING");
        assertThat(queryString("SELECT status FROM embedding_job_attempts WHERE id = ?", context.attemptId()))
            .isEqualTo("STARTED");
        assertThat(eventCount(context.jobId(), "PARSE_STARTED")).isOne();
        assertThat(eventCount(context.jobId(), "CHUNKED")).isOne();
    }

    @Test
    @DisplayName("같은 실행의 두 요청은 생성과 재생 하나씩 및 단일 Chunk Set으로 수렴한다")
    void createChunks_concurrentRequestsConverge() throws Exception {
        ExecutionContext context = insertExecution("abcdefgh");
        CreateDocumentChunksRequest request = new CreateDocumentChunksRequest(context.workerId(), CLAIM_TOKEN);
        fileStorage.armReadBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<ChunkResult> results;
        try {
            List<Future<ChunkResult>> futures = List.of(
                executor.submit(() -> documentParsingService.createChunks(
                    context.jobId(), context.attemptId(), request
                )),
                executor.submit(() -> documentParsingService.createChunks(
                    context.jobId(), context.attemptId(), request
                ))
            );
            results = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            fileStorage.disarmReadBarrier();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).filteredOn(ChunkResult::created).hasSize(1);
        assertThat(results).filteredOn(result -> !result.created()).hasSize(1);
        assertThat(results).extracting(result -> result.response().chunkCount()).containsOnly(3);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            Integer.class,
            context.versionId()
        )).isEqualTo(3);
        assertThat(eventCount(context.jobId(), "PARSE_STARTED")).isOne();
        assertThat(eventCount(context.jobId(), "CHUNKED")).isOne();
    }

    @Test
    @DisplayName("PDF Page Segment를 Page Number와 전역 Offset이 있는 Chunk로 저장한다")
    void createChunks_persistsPdfPageMetadata() throws IOException {
        ExecutionContext context = insertExecution(
            pdfWithPages("abcd", "wxyz"),
            "pdf",
            "application/pdf",
            "PDF"
        );
        CreateDocumentChunksRequest request = new CreateDocumentChunksRequest(context.workerId(), CLAIM_TOKEN);

        ChunkResult result = documentParsingService.createChunks(context.jobId(), context.attemptId(), request);

        assertThat(result.response().chunkCount()).isEqualTo(2);
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList("""
            SELECT chunk_index, chunk_text, char_start, char_end, page_no, section_title
            FROM document_chunks
            WHERE document_version_id = ?
            ORDER BY chunk_index
            """, context.versionId());
        assertThat(chunks).extracting(row -> row.get("chunk_text")).containsExactly("abcd", "wxyz");
        assertThat(chunks).extracting(row -> row.get("page_no")).containsExactly(1, 2);
        assertThat(chunks).extracting(row -> row.get("char_start")).containsExactly(0, 5);
        assertThat(chunks).extracting(row -> row.get("char_end")).containsExactly(4, 9);
        assertThat(chunks).extracting(row -> row.get("section_title")).containsOnlyNulls();
    }

    @Test
    @DisplayName("DOCX Section Segment를 Section Title과 전역 Offset이 있는 Chunk로 저장한다")
    void createChunks_persistsDocxSectionMetadata() throws IOException {
        ExecutionContext context = insertExecution(
            docxWithSections(),
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "DOCX"
        );
        CreateDocumentChunksRequest request = new CreateDocumentChunksRequest(context.workerId(), CLAIM_TOKEN);

        ChunkResult result = documentParsingService.createChunks(context.jobId(), context.attemptId(), request);

        assertThat(result.response().chunkCount()).isEqualTo(2);
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList("""
            SELECT chunk_index, chunk_text, char_start, char_end, page_no, section_title
            FROM document_chunks
            WHERE document_version_id = ?
            ORDER BY chunk_index
            """, context.versionId());
        assertThat(chunks).extracting(row -> row.get("chunk_text")).containsExactly("A\nbc", "D\nef");
        assertThat(chunks).extracting(row -> row.get("section_title")).containsExactly("A", "D");
        assertThat(chunks).extracting(row -> row.get("char_start")).containsExactly(0, 5);
        assertThat(chunks).extracting(row -> row.get("char_end")).containsExactly(4, 9);
        assertThat(chunks).extracting(row -> row.get("page_no")).containsOnlyNulls();
    }

    @Test
    @DisplayName("Text가 없는 PDF 파싱 실패 시 Chunk를 부분 저장하지 않는다")
    void createChunks_doesNotPersistChunksWhenPdfRequiresOcr() throws IOException {
        ExecutionContext context = insertExecution(
            pdfWithPages((String) null),
            "pdf",
            "application/pdf",
            "PDF"
        );
        CreateDocumentChunksRequest request = new CreateDocumentChunksRequest(context.workerId(), CLAIM_TOKEN);

        assertThatThrownBy(() -> documentParsingService.createChunks(context.jobId(), context.attemptId(), request))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_OCR_REQUIRED));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            Integer.class,
            context.versionId()
        )).isZero();
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", context.versionId()))
            .isEqualTo("PARSING");
        assertThat(eventCount(context.jobId(), "CHUNKED")).isZero();
    }

    private ExecutionContext insertExecution(String content) {
        return insertExecution(
            content.getBytes(StandardCharsets.UTF_8),
            "txt",
            "text/plain",
            "TXT"
        );
    }

    private ExecutionContext insertExecution(
        byte[] contentBytes,
        String extension,
        String contentType,
        String documentType
    ) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Chunk Test User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "chunk-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at, created_at, updated_at
            )
            VALUES ('chunk-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        StoredFile storedFile = new StoredFile(
            StorageProvider.MINIO,
            "chunk-test-bucket",
            "source-" + suffix + "." + extension
        );
        fileStorage.put(storedFile, contentBytes);
        Long fileObjectId = jdbcTemplate.queryForObject("""
            INSERT INTO file_objects (
                bucket_name, object_key, original_filename, content_type, file_size, file_hash,
                storage_provider, uploaded_by, uploaded_at, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, 'MINIO', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class,
            storedFile.bucketName(),
            storedFile.objectKey(),
            "source." + extension,
            contentType,
            contentBytes.length,
            suffix,
            userId);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            )
            VALUES (?, 'Chunk Test Document', ?, 'UPLOAD', 'INDEXING', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, documentType);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, file_object_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, ?, 1, 'Chunk Test Version', ?, 'UPLOADED', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, fileObjectId, contentType, userId);
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            LIMIT 1
            """, Long.class);
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count, max_retry_count,
                locked_by_worker_id, locked_at, lock_expires_at, claim_token, started_at, created_at, updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, CURRENT_TIMESTAMP,
                    TIMESTAMP '2099-01-01 00:00:00', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, workerId, CLAIM_TOKEN);
        Long attemptId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, created_at, updated_at
            )
            VALUES (?, ?, 1, ?, 'STARTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, CLAIM_TOKEN);
        return new ExecutionContext(workerId, jobId, attemptId, versionId);
    }

    private byte[] pdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageText != null) {
                    try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                        contentStream.beginText();
                        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        contentStream.newLineAtOffset(72, 720);
                        contentStream.showText(pageText);
                        contentStream.endText();
                    }
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docxWithSections() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            addSection(document, "A", "bc");
            addSection(document, "D", "ef");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private void addSection(XWPFDocument document, String title, String body) {
        XWPFParagraph heading = document.createParagraph();
        heading.setStyle("Heading1");
        heading.createRun().setText(title);
        document.createParagraph().createRun().setText(body);
    }

    private String queryString(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private int eventCount(Long jobId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = ?",
            Integer.class,
            jobId,
            eventType
        );
    }

    /**
     * 통합 테스트 실행 Context의 Worker, Job, Attempt와 Version 식별자를 묶는다.
     */
    private record ExecutionContext(
        Long workerId,
        Long jobId,
        Long attemptId,
        Long versionId
    ) {
    }

    /**
     * 실제 DB Transaction 밖의 파일 I/O와 동시 진입을 제어할 테스트 Storage Bean을 제공한다.
     */
    @TestConfiguration
    static class ChunkStorageTestConfig {

        @Bean
        @Primary
        InMemoryChunkFileStorage inMemoryChunkFileStorage() {
            return new InMemoryChunkFileStorage();
        }
    }

    /**
     * Bucket·Object Key별 Byte를 보관하고 선택적으로 두 읽기 요청을 Barrier에 모으는 테스트 Storage.
     */
    static class InMemoryChunkFileStorage implements FileStorageService {

        private final Map<StoredFile, byte[]> files = new ConcurrentHashMap<>();
        private final AtomicReference<CyclicBarrier> readBarrier = new AtomicReference<>();

        @Override
        public StoredFile store(InputStream inputStream, long fileSize, String contentType, String objectKey) {
            throw new UnsupportedOperationException("Chunk 통합 테스트에서는 store를 사용하지 않습니다.");
        }

        @Override
        public byte[] read(StoredFile storedFile) {
            CyclicBarrier barrier = readBarrier.get();
            if (barrier != null) {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Chunk 통합 테스트 읽기 Barrier 대기에 실패했습니다.", exception);
                }
            }
            return files.get(storedFile).clone();
        }

        @Override
        public Stream<StorageObjectMetadata> streamObjects(String prefix, int pageSize) {
            return Stream.empty();
        }

        @Override
        public void delete(StoredFile storedFile) {
            files.remove(storedFile);
        }

        void put(StoredFile storedFile, byte[] content) {
            files.put(storedFile, content.clone());
        }

        void armReadBarrier(int parties) {
            readBarrier.set(new CyclicBarrier(parties));
        }

        void disarmReadBarrier() {
            CyclicBarrier barrier = readBarrier.getAndSet(null);
            if (barrier != null) {
                barrier.reset();
            }
        }

        void reset() {
            disarmReadBarrier();
            files.clear();
        }
    }
}
