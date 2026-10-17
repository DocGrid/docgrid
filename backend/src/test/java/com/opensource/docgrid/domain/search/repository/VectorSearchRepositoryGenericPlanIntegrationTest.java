package com.opensource.docgrid.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실제 PostgreSQL에서 벡터 검색 쿼리({@link VectorSearchRepository#findTopK})가 Generic Plan에서도 질문 Vector를
 * 한 번만 계산하는지 검증한다(#549).
 *
 * <p>같은 SQL이 반복되면 PostgreSQL은 값과 무관한 Generic Plan을 재사용한다. 이때 {@code CAST(:queryVector AS vector)}를
 * 그대로 두면 1024차원 문자열을 행마다 다시 파싱해 검색이 수십 배 느려진다. 스칼라 서브쿼리로 감싸 InitPlan으로 한 번만
 * 계산하는지, 결과가 Custom Plan과 같은지, HNSW 정렬 Index Scan도 여전히 쓸 수 있는지를 실행 계획으로 확인한다.
 * 실행 계획 검사는 운영 Repository의 {@code @Query} SQL을 그대로 읽어 PREPARE한다.
 */
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("벡터 검색 쿼리 Generic Plan 통합 테스트")
class VectorSearchRepositoryGenericPlanIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_vector_search_generic_plan_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int TOP_K = 10;
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final String QUERY_VECTOR = vector(0.0);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VectorSearchRepository vectorSearchRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Long userId;
    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-vector-search-generic-plan-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE embeddings, document_chunks, document_versions, documents, users
            RESTART IDENTITY CASCADE
            """);
        userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Generic Plan Test User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "vector-search-generic-plan-" + UUID.randomUUID() + "@example.com");
        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Generic Plan으로 실행해도 Custom Plan과 같은 청크를 같은 거리 순서로 돌려준다")
    void findTopK_genericPlanReturnsSameRowsAsCustomPlan() {
        Long documentId = documentWithChunks(4);

        jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_custom_plan");
        List<VectorSearchRow> custom = findTopK(documentId);
        jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_generic_plan");
        List<VectorSearchRow> generic = findTopK(documentId);

        assertThat(custom).hasSize(4);
        assertThat(generic).extracting(VectorSearchRow::getChunkId)
            .containsExactlyElementsOf(custom.stream().map(VectorSearchRow::getChunkId).toList());
        assertThat(generic).extracting(VectorSearchRow::getDistance)
            .containsExactlyElementsOf(custom.stream().map(VectorSearchRow::getDistance).toList());
    }

    @Test
    @DisplayName("Generic Plan의 정렬 키는 행마다 질문 Vector를 변환하지 않고 InitPlan 결과를 쓴다")
    void findTopK_genericPlanEvaluatesQueryVectorOnce() throws Exception {
        Long documentId = documentWithChunks(4);

        JsonNode plan = explainGenericTopK(documentId);

        List<String> orderingKeys = orderingKeys(plan);
        assertThat(orderingKeys).isNotEmpty();
        assertThat(orderingKeys).noneMatch(key -> key.contains("$1"));
        assertThat(plan.toString()).contains("InitPlan");
    }

    @Test
    @DisplayName("질문 Vector를 서브쿼리로 감싸도 HNSW 정렬 Index Scan을 쓸 수 있다")
    void findTopK_genericPlanCanStillUseHnswIndex() throws Exception {
        Long documentId = documentWithChunks(4);
        String hnswIndex = jdbcTemplate.queryForObject("""
            SELECT indexname FROM pg_indexes
            WHERE schemaname = current_schema() AND tablename = 'embeddings' AND indexdef ILIKE '%USING hnsw%'
            """, String.class);
        jdbcTemplate.execute("SET LOCAL enable_sort = off");
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

        JsonNode plan = explainGenericTopK(documentId);

        assertThat(indexNames(plan)).contains(hnswIndex);
        assertThat(orderingKeys(plan)).isNotEmpty().noneMatch(key -> key.contains("$1"));
    }

    private List<VectorSearchRow> findTopK(Long documentId) {
        return vectorSearchRepository.findTopK(QUERY_VECTOR, embeddingModelId, List.of(documentId), TOP_K);
    }

    /**
     * 운영 findTopK SQL을 허용 문서 1건짜리 Prepared Statement로 만들고 Generic Plan을 강제해 실행 계획을 읽는다.
     */
    private JsonNode explainGenericTopK(Long documentId) throws Exception {
        String sql = VectorSearchRepository.class
            .getMethod("findTopK", String.class, Long.class, List.class, int.class)
            .getAnnotation(Query.class)
            .value()
            .replace(":queryVector", "$1")
            .replace(":modelId", "$2")
            .replace("(:permittedIds)", "($3)")
            .replace(":topK", "$4");
        String statement = "generic_plan_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("PREPARE " + statement + "(text, bigint, bigint, int) AS " + sql);
        try {
            jdbcTemplate.execute("SET LOCAL plan_cache_mode = force_generic_plan");
            String explain = jdbcTemplate.queryForObject(
                "EXPLAIN (VERBOSE, FORMAT JSON) EXECUTE " + statement + "('" + QUERY_VECTOR + "', "
                    + embeddingModelId + ", " + documentId + ", " + TOP_K + ")",
                String.class);
            return objectMapper.readTree(explain).get(0).path("Plan");
        } finally {
            jdbcTemplate.execute("DEALLOCATE " + statement);
        }
    }

    /** 실행 계획 전체에서 정렬 기준식(Sort Key, Index Scan의 Order By)을 모은다. */
    private static List<String> orderingKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        collect(node, keys, new ArrayList<>());
        return keys;
    }

    private static List<String> indexNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        collect(node, new ArrayList<>(), names);
        return names;
    }

    private static void collect(JsonNode node, List<String> orderingKeys, List<String> indexNames) {
        node.path("Sort Key").forEach(key -> orderingKeys.add(key.asText()));
        if (node.has("Order By")) {
            orderingKeys.add(node.path("Order By").asText());
        }
        if (node.has("Index Name")) {
            indexNames.add(node.path("Index Name").asText());
        }
        node.path("Plans").forEach(child -> collect(child, orderingKeys, indexNames));
    }

    private Long documentWithChunks(int chunkCount) {
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            )
            VALUES (?, '규정', 'DOCX', 'UPLOAD', 'INDEXED', 'PRIVATE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status, created_by, created_at, updated_at
            )
            VALUES (?, 1, '규정', 'text/plain', 'INDEXED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
        jdbcTemplate.update("UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId);

        for (int index = 0; index < chunkCount; index++) {
            String text = "규정 본문 " + index;
            Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO document_chunks (
                    document_version_id, chunk_index, chunk_text, token_count, char_start, char_end,
                    content_hash, created_at, updated_at
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, versionId, index, text, index * 100, index * 100 + text.length(), CONTENT_HASH);
            jdbcTemplate.update("""
                INSERT INTO embeddings (
                    chunk_id, document_id, document_version_id, embedding_model_id,
                    vector, dimension, vector_hash, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, chunkId, documentId, versionId, embeddingModelId, vector(index * 0.5), VECTOR_DIMENSION,
                CONTENT_HASH);
        }
        return documentId;
    }

    /** 첫 성분은 1, 둘째 성분은 secondComponent, 나머지는 0인 Vector 문자열이다. 0이면 질의 Vector가 된다. */
    private static String vector(double secondComponent) {
        StringBuilder builder = new StringBuilder("[1.0,").append(secondComponent);
        for (int i = 2; i < VECTOR_DIMENSION; i++) {
            builder.append(",0.0");
        }
        return builder.append(']').toString();
    }
}
