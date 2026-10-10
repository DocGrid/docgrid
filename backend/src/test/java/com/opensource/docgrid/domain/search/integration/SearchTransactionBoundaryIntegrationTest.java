package com.opensource.docgrid.domain.search.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.converter.EmbeddingModelConverter;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.domain.search.service.command.SearchConversationCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchResultCommandService;
import com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService;
import com.opensource.docgrid.domain.search.service.query.HybridSearchQueryService;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 실제 PostgreSQL에서 검색 실패 원장의 독립 커밋과 외부 임베딩 호출의 트랜잭션 경계를 검증한다.
 *
 * <p>테스트 자체의 트랜잭션을 끄고 Facade 프록시가 여는 경계만 관찰한다. 임베딩 Client는
 * mock으로 대체하되 호출 순간의 Spring 트랜잭션과 Hikari 활성 연결 수를 함께 기록한다.
 */
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    SearchFacade.class,
    SearchConversationCommandService.class,
    SearchQueryCommandService.class,
    SearchResultCommandService.class,
    QueryEmbeddingService.class,
    EmbeddingModelQueryService.class,
    EmbeddingModelConverter.class
})
@DisplayName("검색 실패 원장 트랜잭션 경계 통합 테스트")
class SearchTransactionBoundaryIntegrationTest {

    private static final int VECTOR_DIMENSION = 1024;

    @Autowired private SearchFacade searchFacade;
    @Autowired private SearchQueryCommandService searchQueryCommandService;
    @Autowired private SearchQueryRepository searchQueryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmbeddingModelRepository embeddingModelRepository;
    @Autowired private DataSource dataSource;

    @MockitoBean private EmbeddingClient embeddingClient;
    @MockitoBean private SearchConversationQueryService searchConversationQueryService;
    @MockitoBean private AccessibleDocumentQueryService accessibleDocumentQueryService;
    @MockitoBean private VectorSearchQueryService vectorSearchQueryService;
    // 하이브리드는 꺼진 상태(isEnabled=false)로 두어 이 테스트가 기존 벡터 검색 경로의 트랜잭션 경계를 그대로 검증한다.
    @MockitoBean private HybridSearchQueryService hybridSearchQueryService;
    @MockitoBean private HybridSearchProperties hybridSearchProperties;
    @MockitoBean private PermissionQueryService permissionQueryService;

    private JdbcTemplate jdbcTemplate;
    private String marker;
    private Long userId;
    private Long modelId;

    @BeforeEach
    void setUp() {
        reset(
            embeddingClient,
            searchConversationQueryService,
            accessibleDocumentQueryService,
            vectorSearchQueryService,
            permissionQueryService
        );
        jdbcTemplate = new JdbcTemplate(dataSource);
        marker = "search-failure-" + UUID.randomUUID();
        userId = userRepository.saveAndFlush(User.builder()
            .email(marker + "@test.com")
            .passwordHash("hash")
            .name("검색 실패 테스트 사용자")
            .status(UserStatus.ACTIVE)
            .build()).getId();
        modelId = embeddingModelRepository.findAllByIsActiveTrueAndIsSearchableTrue()
            .stream()
            .findFirst()
            .orElseThrow()
            .getId();

        given(searchConversationQueryService.findRecentContext(anyLong(), anyLong(), eq(2)))
            .willReturn(List.of());
        given(searchConversationQueryService.contextualizeRetrieval(anyString(), any()))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM search_results WHERE query_id IN "
            + "(SELECT id FROM search_queries WHERE query_text = ?)", marker);
        jdbcTemplate.update("DELETE FROM search_queries WHERE query_text = ?", marker);
        jdbcTemplate.update("DELETE FROM search_conversations WHERE user_id = ?", userId);
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("임베딩 실패 시 DB 연결 없이 호출하고 FAILED 원장을 남긴다")
    void search_persistsFailedLedgerWithoutTransaction_whenEmbeddingFails() {
        AtomicBoolean transactionActive = new AtomicBoolean();
        AtomicInteger activeConnections = new AtomicInteger();
        given(embeddingClient.embed(anyString())).willAnswer(invocation -> {
            captureTransactionMetrics(transactionActive, activeConnections);
            throw new IllegalStateException("forced embedding failure");
        });

        assertThatThrownBy(() -> searchFacade.search(userId, request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forced embedding failure");

        assertThat(failureMetrics(transactionActive, activeConnections))
            .isEqualTo(FailureMetrics.fixed());
    }

    @Test
    @DisplayName("원장 생성 뒤 검색이 실패해도 독립 트랜잭션으로 FAILED를 남긴다")
    void search_persistsFailedLedger_whenSearchFailsAfterProcessingCommit() {
        AtomicBoolean transactionActive = new AtomicBoolean();
        AtomicInteger activeConnections = new AtomicInteger();
        given(embeddingClient.embed(anyString())).willAnswer(invocation -> {
            captureTransactionMetrics(transactionActive, activeConnections);
            return new float[VECTOR_DIMENSION];
        });
        given(accessibleDocumentQueryService.findReadableDocumentIds(userId, null))
            .willThrow(new IllegalStateException("forced search failure"));

        assertThatThrownBy(() -> searchFacade.search(userId, request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("forced search failure");

        assertThat(failureMetrics(transactionActive, activeConnections))
            .isEqualTo(FailureMetrics.fixed());
    }

    @Test
    @DisplayName("성공 시 임베딩 정보와 SUCCESS를 커밋하고 늦은 실패가 덮어쓰지 못한다")
    void search_commitsEmbeddingAndSuccessAndRejectsLateFailure_whenNoDocuments() {
        AtomicBoolean transactionActive = new AtomicBoolean();
        AtomicInteger activeConnections = new AtomicInteger();
        float[] vector = new float[VECTOR_DIMENSION];
        given(embeddingClient.embed(anyString())).willAnswer(invocation -> {
            captureTransactionMetrics(transactionActive, activeConnections);
            return vector;
        });
        given(accessibleDocumentQueryService.findReadableDocumentIds(userId, null)).willReturn(List.of());

        SearchOutcome outcome = searchFacade.search(userId, request());

        SearchQuery persisted = searchQueryRepository.findById(outcome.response().queryId()).orElseThrow();
        assertThat(transactionActive).isFalse();
        assertThat(activeConnections).hasValue(0);
        assertThat(persisted.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT query_embedding_model_id FROM search_queries WHERE id = ?",
            Long.class,
            persisted.getId()
        )).isEqualTo(modelId);
        assertThat(persisted.getQueryVector()).containsExactly(vector);
        assertThat(searchQueryCommandService.markFailed(persisted.getId(), "late failure")).isFalse();
        assertThat(searchQueryRepository.findById(persisted.getId()).orElseThrow().getStatus())
            .isEqualTo(ResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("결과 저장이 롤백되면 부분 결과 없이 원장만 FAILED로 확정한다")
    void search_rollsBackPartialSuccessAndPersistsFailure_whenResultInsertFails() {
        given(embeddingClient.embed(anyString())).willReturn(new float[VECTOR_DIMENSION]);
        given(accessibleDocumentQueryService.findReadableDocumentIds(userId, null)).willReturn(List.of(99L));
        given(vectorSearchQueryService.search(any(), eq(modelId), eq(List.of(99L)), eq(5)))
            .willReturn(List.of(new VectorSearchCandidate(
                9_999_998L, 9_999_999L, 99L, "존재하지 않는 청크", 1, "문서", BigDecimal.ONE
            )));
        given(permissionQueryService.canReadDocument(userId, 99L)).willReturn(true);

        assertThatThrownBy(() -> searchFacade.search(userId, request()))
            .isInstanceOf(RuntimeException.class);

        SearchQuery persisted = searchQueryRepository.findAll().stream()
            .filter(query -> marker.equals(query.getQueryText()))
            .findFirst()
            .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(persisted.getQueryEmbeddingModel()).isNull();
        assertThat(persisted.getQueryVector()).isNull();
        assertThat(count("SELECT COUNT(*) FROM search_results WHERE query_id IN "
            + "(SELECT id FROM search_queries WHERE query_text = ?)")).isZero();
    }

    private SearchRequest request() {
        return new SearchRequest(marker, 5, null);
    }

    private void captureTransactionMetrics(
        AtomicBoolean transactionActive,
        AtomicInteger activeConnections
    ) throws Exception {
        transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        activeConnections.set(hikariDataSource.getHikariPoolMXBean().getActiveConnections());
    }

    private FailureMetrics failureMetrics(
        AtomicBoolean transactionActive,
        AtomicInteger activeConnections
    ) {
        int queryRows = count("SELECT COUNT(*) FROM search_queries WHERE query_text = ?");
        int failedRows = count("SELECT COUNT(*) FROM search_queries WHERE query_text = ? AND status = 'FAILED'");
        int processingRows = count(
            "SELECT COUNT(*) FROM search_queries WHERE query_text = ? AND status = 'PROCESSING'"
        );
        return new FailureMetrics(
            transactionActive.get(),
            activeConnections.get(),
            queryRows,
            failedRows,
            processingRows
        );
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, marker);
    }

    /** 외부 호출 순간의 자원 점유와 호출 실패 뒤 검색 원장 상태를 함께 표현한다. */
    private record FailureMetrics(
        boolean transactionActiveDuringEmbedding,
        int activeConnectionsDuringEmbedding,
        int queryRows,
        int failedRows,
        int processingRows
    ) {
        private static FailureMetrics fixed() {
            return new FailureMetrics(false, 0, 1, 1, 0);
        }
    }
}
