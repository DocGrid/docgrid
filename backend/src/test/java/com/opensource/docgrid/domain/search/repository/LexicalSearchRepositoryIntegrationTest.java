package com.opensource.docgrid.domain.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

/**
 * 실제 PostgreSQL에서 하이브리드 검색의 단어 갈래 쿼리(문서 빈도 조회와 커버리지 조회)가 질문 단어를 가진 청크를 찾고,
 * 벡터 쿼리와 같은 권한·상태 조건을 지키는지 검증한다.
 *
 * <p>가장 중요한 것은 권한 누수 방지다. 단어 갈래는 벡터 갈래와 별개의 SQL이라 한쪽 조건이 빠지면 읽기 권한이 없거나
 * 삭제된 문서가 이 경로로 노출되고, 문서 빈도(IDF)에도 그 문서의 단어 분포가 섞인다. 허용 목록 밖 문서, 삭제 문서,
 * 미색인 문서, 이전 버전 청크, 비활성 임베딩, 다른 임베딩 모델의 청크가 각각 결과와 빈도에 반영되지 않는지 하나씩 확인한다.
 * 마이그레이션(V46)이 테스트 스키마에서도 확장과 인덱스를 만드는지는 이 테스트가 기동되는 것으로 함께 검증된다.
 */
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("단어 갈래 Repository 통합 테스트")
class LexicalSearchRepositoryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_lexical_search_repository_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final String QUERY_VECTOR = vector(0.0);
    private static final String OM = "(^|[^a-z0-9])om([^a-z0-9]|$)";
    private static final String DIRECTION = "방향";
    private static final List<Long> NO_VECTOR_CHUNKS = List.of(-1L);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VectorSearchRepository vectorSearchRepository;

    private Long userId;
    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-lexical-search-repository-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE embeddings, document_chunks, document_versions, documents, users
            RESTART IDENTITY CASCADE
            """);
        userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Lexical Search Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "lexical-search-repository-" + UUID.randomUUID() + "@example.com");
        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("문서 빈도: 검색 범위 전체 청크 수와 단어별 청크 수를 센다")
    void countPatternMatches_returnsScopeTotalAndTermCounts() {
        Doc doc = indexedDocument("회칙");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        chunkWithEmbedding(doc, 1, "정규 세션은 매주 금요일에 진행한다.");
        chunkWithEmbedding(doc, 2, "클라이언트에서 서버 방향으로 보낸다.");

        List<PatternCountRow> rows = vectorSearchRepository.countPatternMatches(
            OM + "\n" + DIRECTION, embeddingModelId, List.of(doc.documentId())
        );

        assertThat(rows).extracting(PatternCountRow::getOrd).containsExactly(0, 1, 2);
        assertThat(rows).extracting(PatternCountRow::getMatchCount).containsExactly(3L, 1L, 1L);
    }

    @Test
    @DisplayName("단어 경계: 영문 단어는 다른 영문 안에 들어 있으면 일치로 보지 않는다")
    void countPatternMatches_asciiTermRequiresWordBoundary() {
        Doc doc = indexedDocument("영문");
        chunkWithEmbedding(doc, 0, "Computer 클래스는 하드웨어를 점검한다.");
        chunkWithEmbedding(doc, 1, "OM(Old Member)은 다음 기수에 지원할 수 있다.");
        chunkWithEmbedding(doc, 2, "om이라는 소문자 표기도 있다.");

        List<PatternCountRow> rows = vectorSearchRepository.countPatternMatches(OM, embeddingModelId, List.of(doc.documentId()));

        // Computer 안의 om은 세지 않고, OM(대소문자 무시)과 om이 붙은 한글 조사 앞의 om만 센다.
        assertThat(rows).extracting(PatternCountRow::getMatchCount).containsExactly(3L, 2L);
    }

    @Test
    @DisplayName("커버리지: 단어 가중치 합 비율로 계산하고 큰 순서로 정렬하며 벡터 거리도 함께 돌려준다")
    void findLexicalCandidates_returnsWeightedCoverageOrderedWithDistance() {
        Doc doc = indexedDocument("회칙");
        Long both = chunkWithEmbedding(doc, 0, "OM은 다음 기수에 신청하는 방향을 정한다.");
        Long onlyOm = chunkWithEmbedding(doc, 1, "OM은 벌점 14점 이하인 회원이다.");
        Long onlyDirection = chunkWithEmbedding(doc, 2, "클라이언트에서 서버 방향으로 보낸다.");
        chunkWithEmbedding(doc, 3, "정규 세션은 매주 금요일에 진행한다.");

        // OM의 가중치 3, 방향의 가중치 1 → 합 4
        List<LexicalSearchRow> rows = vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM + "\n" + DIRECTION, "3.0\n1.0", 4.0, 0.2, embeddingModelId,
            List.of(doc.documentId()), NO_VECTOR_CHUNKS, 10
        );

        assertThat(rows).extracting(LexicalSearchRow::getChunkId).containsExactly(both, onlyOm, onlyDirection);
        assertThat(rows).extracting(LexicalSearchRow::getCoverage).containsExactly(1.0, 0.75, 0.25);
        assertThat(rows.get(0).getDistance()).isNotNull();
        assertThat(rows.get(0).getDocumentTitle()).isEqualTo("회칙");
    }

    @Test
    @DisplayName("최소 커버리지 미만 청크는 후보에서 빠지고 limit만큼만 돌려준다")
    void findLexicalCandidates_appliesMinCoverageAndLimit() {
        Doc doc = indexedDocument("회칙");
        Long onlyOm = chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        chunkWithEmbedding(doc, 1, "OM은 신청할 수 있다.");
        chunkWithEmbedding(doc, 2, "클라이언트에서 서버 방향으로 보낸다.");

        List<LexicalSearchRow> strict = vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM + "\n" + DIRECTION, "3.0\n1.0", 4.0, 0.5, embeddingModelId,
            List.of(doc.documentId()), NO_VECTOR_CHUNKS, 10
        );
        List<LexicalSearchRow> limited = vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM + "\n" + DIRECTION, "3.0\n1.0", 4.0, 0.5, embeddingModelId,
            List.of(doc.documentId()), NO_VECTOR_CHUNKS, 1
        );

        assertThat(strict).hasSize(2).extracting(LexicalSearchRow::getCoverage).allMatch(c -> c >= 0.5);
        assertThat(limited).extracting(LexicalSearchRow::getChunkId).containsExactly(onlyOm);
    }

    @Test
    @DisplayName("벡터 후보로 넘긴 청크는 최소 커버리지 미만이어도 커버리지를 함께 돌려준다")
    void findLexicalCandidates_includesVectorCandidatesBelowMinCoverage() {
        Doc doc = indexedDocument("회칙");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        Long weak = chunkWithEmbedding(doc, 1, "클라이언트에서 서버 방향으로 보낸다.");
        Long noMatch = chunkWithEmbedding(doc, 2, "정규 세션은 매주 금요일에 진행한다.");

        List<LexicalSearchRow> rows = vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM + "\n" + DIRECTION, "3.0\n1.0", 4.0, 0.5, embeddingModelId,
            List.of(doc.documentId()), List.of(weak, noMatch), 10
        );

        // 방향만 가진 청크(0.25)는 벡터 후보라서 포함되고, 단어가 하나도 없는 청크는 단어 갈래에 나오지 않는다(커버리지 0으로 취급).
        assertThat(rows).extracting(LexicalSearchRow::getChunkId).contains(weak).doesNotContain(noMatch);
        assertThat(rows.stream().filter(r -> r.getChunkId().equals(weak)).findFirst().orElseThrow().getCoverage())
            .isCloseTo(0.25, within(1e-9));
    }

    @Test
    @DisplayName("권한 누수 방지: 허용 문서 목록에 없는 문서의 청크는 결과와 문서 빈도에 반영되지 않는다")
    void documentOutsidePermittedIds_isExcluded() {
        Doc allowed = indexedDocument("허용 문서");
        Doc forbidden = indexedDocument("권한 없는 문서");
        Long allowedChunk = chunkWithEmbedding(allowed, 0, "OM은 벌점 14점 이하인 회원이다.");
        chunkWithEmbedding(forbidden, 0, "OM은 벌점 14점 이하인 회원이다.");

        assertThat(lexicalChunkIds(allowed.documentId())).containsExactly(allowedChunk);
        assertThat(counts(allowed.documentId())).containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("권한 누수 방지: 삭제된 문서의 청크는 나오지 않는다")
    void deletedDocument_isExcluded() {
        Doc doc = indexedDocument("삭제될 문서");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        jdbcTemplate.update("UPDATE documents SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", doc.documentId());

        assertThat(lexicalChunkIds(doc.documentId())).isEmpty();
        assertThat(counts(doc.documentId())).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("권한 누수 방지: 색인이 끝나지 않은 문서의 청크는 나오지 않는다")
    void notIndexedDocument_isExcluded() {
        Doc doc = indexedDocument("색인 중 문서");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        jdbcTemplate.update("UPDATE documents SET status = 'INDEXING' WHERE id = ?", doc.documentId());

        assertThat(lexicalChunkIds(doc.documentId())).isEmpty();
        assertThat(counts(doc.documentId())).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("권한 누수 방지: 현재 버전이 아닌 이전 버전의 청크는 나오지 않는다")
    void olderVersionChunk_isExcluded() {
        Doc doc = indexedDocument("버전이 바뀐 문서");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        Long newVersionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status, created_by, created_at, updated_at
            )
            VALUES (?, 2, '버전이 바뀐 문서', 'text/plain', 'INDEXED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, doc.documentId(), userId);
        jdbcTemplate.update("UPDATE documents SET current_version_id = ? WHERE id = ?", newVersionId, doc.documentId());

        assertThat(lexicalChunkIds(doc.documentId())).isEmpty();
        assertThat(counts(doc.documentId())).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("권한 누수 방지: 활성 상태가 아닌 임베딩의 청크는 나오지 않는다")
    void inactiveEmbedding_isExcluded() {
        Doc doc = indexedDocument("오래된 임베딩 문서");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        jdbcTemplate.update("UPDATE embeddings SET status = 'STALE' WHERE document_id = ?", doc.documentId());

        assertThat(lexicalChunkIds(doc.documentId())).isEmpty();
        assertThat(counts(doc.documentId())).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("다른 임베딩 모델의 청크는 나오지 않는다")
    void otherEmbeddingModel_isExcluded() {
        Doc doc = indexedDocument("모델 불일치 문서");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");

        List<LexicalSearchRow> rows = vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM, "2.0", 2.0, 0.1, embeddingModelId + 1000, List.of(doc.documentId()), NO_VECTOR_CHUNKS, 10
        );
        List<PatternCountRow> counts = vectorSearchRepository.countPatternMatches(OM, embeddingModelId + 1000, List.of(doc.documentId()));

        assertThat(rows).isEmpty();
        assertThat(counts).extracting(PatternCountRow::getMatchCount).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("순차 스캔을 끄고 인덱스를 쓰게 해도 같은 결과가 나온다")
    void sameResultsWhenIndexIsPreferred() {
        Doc doc = indexedDocument("회칙");
        chunkWithEmbedding(doc, 0, "OM은 벌점 14점 이하인 회원이다.");
        chunkWithEmbedding(doc, 1, "Computer 클래스는 하드웨어를 점검한다.");
        chunkWithEmbedding(doc, 2, "클라이언트에서 서버 방향으로 보낸다.");
        List<Long> expected = lexicalChunkIds(doc.documentId());

        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        List<Long> withIndex = lexicalChunkIds(doc.documentId());

        assertThat(withIndex).containsExactlyElementsOf(expected);
    }

    private List<Long> lexicalChunkIds(Long documentId) {
        return vectorSearchRepository.findLexicalCandidates(
            QUERY_VECTOR, OM + "\n" + DIRECTION, "3.0\n1.0", 4.0, 0.2, embeddingModelId,
            List.of(documentId), NO_VECTOR_CHUNKS, 10
        ).stream().map(LexicalSearchRow::getChunkId).toList();
    }

    private List<Long> counts(Long documentId) {
        // ord 0(범위 전체)과 첫 단어(OM)의 청크 수만 비교한다.
        return vectorSearchRepository.countPatternMatches(OM, embeddingModelId, List.of(documentId))
            .stream().map(PatternCountRow::getMatchCount).toList();
    }

    private Doc indexedDocument(String title) {
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            )
            VALUES (?, ?, 'DOCX', 'UPLOAD', 'INDEXED', 'PRIVATE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, title);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status, created_by, created_at, updated_at
            )
            VALUES (?, 1, ?, 'text/plain', 'INDEXED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, title, userId);
        jdbcTemplate.update("UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId);
        return new Doc(documentId, versionId);
    }

    private Long chunkWithEmbedding(Doc doc, int index, String text) {
        Long chunkId = jdbcTemplate.queryForObject("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count, char_start, char_end,
                content_hash, created_at, updated_at
            )
            VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class,
            doc.versionId(), index, text, index * 100, index * 100 + text.codePointCount(0, text.length()),
            CONTENT_HASH);
        jdbcTemplate.update("""
            INSERT INTO embeddings (
                chunk_id, document_id, document_version_id, embedding_model_id,
                vector, dimension, vector_hash, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId, doc.documentId(), doc.versionId(), embeddingModelId,
            vector(index * 0.5), VECTOR_DIMENSION, CONTENT_HASH);
        return chunkId;
    }

    /** 첫 성분은 1, 둘째 성분은 secondComponent, 나머지는 0인 Vector 문자열이다. 0이면 질의 Vector가 된다. */
    private static String vector(double secondComponent) {
        StringBuilder builder = new StringBuilder("[1.0,").append(secondComponent);
        for (int i = 2; i < VECTOR_DIMENSION; i++) {
            builder.append(",0.0");
        }
        return builder.append(']').toString();
    }

    private record Doc(Long documentId, Long versionId) {
    }
}
