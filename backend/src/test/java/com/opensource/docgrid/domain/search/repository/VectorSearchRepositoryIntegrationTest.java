package com.opensource.docgrid.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

/**
 * 실제 PostgreSQL pgvector 검색 쿼리가 Chunk의 Section Title과 경로 Metadata를 함께 조회하는지 검증한다.
 *
 * <p>Vector 순서와 필터 조건은 기존 계약대로 두고, 경로가 있는 Chunk·Section Title만 있는 Chunk·구조 정보가
 * 없는 Chunk가 각각 후보의 {@code sectionPath}로 올바르게 변환되는지만 확인한다.
 */
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("벡터 검색 Repository Section 경로 통합 테스트")
class VectorSearchRepositoryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_vector_search_repository_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final String PATH_JSON =
        "{\"headingPath\":[\"3. 환불 정책\",\"3.2 개봉 후 환불\"],\"headingLevel\":2}";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VectorSearchRepository vectorSearchRepository;

    private Long documentId;
    private Long versionId;
    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-vector-search-repository-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE embeddings, document_chunks, document_versions, documents, users
            RESTART IDENTITY CASCADE
            """);

        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Vector Search Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "vector-search-repository-" + UUID.randomUUID() + "@example.com");
        documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, '쇼핑몰 이용약관', 'DOCX', 'UPLOAD', 'INDEXED', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, '쇼핑몰 이용약관', 'text/plain', 'INDEXED', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
        jdbcTemplate.update("UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId);
        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("검색 행이 Section Title과 경로 Metadata를 담고 후보가 Section 경로로 변환한다")
    void findTopK_returnsSectionColumns() {
        Long pathChunk = insertChunk(0, "개봉한 상품은 배송비를 공제한다.", "3.2 개봉 후 환불", PATH_JSON);
        Long titleOnlyChunk = insertChunk(1, "경로 도입 전 DOCX 청크", "2. 배송", null);
        Long plainChunk = insertChunk(2, "구조 정보가 없는 청크", null, null);
        insertEmbedding(pathChunk, 0);
        insertEmbedding(titleOnlyChunk, 1);
        insertEmbedding(plainChunk, 2);

        List<VectorSearchRow> rows = vectorSearchRepository.findTopK(
            vector(0.0), embeddingModelId, List.of(documentId), 10
        );

        assertThat(rows).extracting(VectorSearchRow::getChunkId)
            .containsExactly(pathChunk, titleOnlyChunk, plainChunk);
        assertThat(rows.get(0).getSectionTitle()).isEqualTo("3.2 개봉 후 환불");
        assertThat(rows.get(0).getMetadataJson()).contains("\"headingPath\"");
        assertThat(rows.get(1).getSectionTitle()).isEqualTo("2. 배송");
        assertThat(rows.get(1).getMetadataJson()).isNull();
        assertThat(rows.get(2).getSectionTitle()).isNull();
        assertThat(rows.get(2).getMetadataJson()).isNull();

        assertThat(rows).extracting(row -> VectorSearchCandidate.from(row).sectionPath())
            .containsExactly("3. 환불 정책 > 3.2 개봉 후 환불", "2. 배송", null);
    }

    private Long insertChunk(int index, String text, String sectionTitle, String metadataJson) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count, char_start, char_end,
                section_title, content_hash, metadata_json, created_at, updated_at
            )
            VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class,
            versionId, index, text, index * 100, index * 100 + text.codePointCount(0, text.length()),
            sectionTitle, CONTENT_HASH, metadataJson);
    }

    /** index가 클수록 질의 Vector와 이루는 각이 커져 검색 순서가 chunk index 순으로 고정된다. */
    private void insertEmbedding(Long chunkId, int index) {
        jdbcTemplate.update("""
            INSERT INTO embeddings (
                chunk_id, document_id, document_version_id, embedding_model_id,
                vector, dimension, vector_hash, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId, documentId, versionId, embeddingModelId,
            vector(index * 0.5), VECTOR_DIMENSION, CONTENT_HASH);
    }

    /** 첫 성분은 1, 둘째 성분은 secondComponent, 나머지는 0인 Vector 문자열이다. 0이면 질의 Vector가 된다. */
    private String vector(double secondComponent) {
        StringBuilder builder = new StringBuilder("[1.0,").append(secondComponent);
        for (int i = 2; i < VECTOR_DIMENSION; i++) {
            builder.append(",0.0");
        }
        return builder.append(']').toString();
    }
}
