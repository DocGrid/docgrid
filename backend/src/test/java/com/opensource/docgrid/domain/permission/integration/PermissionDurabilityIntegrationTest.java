package com.opensource.docgrid.domain.permission.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.*;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.controller.PermissionController;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.enums.*;
import com.opensource.docgrid.domain.permission.repository.*;
import com.opensource.docgrid.domain.permission.service.command.*;
import com.opensource.docgrid.domain.permission.service.query.*;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.GlobalExceptionHandler;

/**
 * 격리 PostgreSQL의 실제 MVC·Service·JPA·Hikari 경로에서 권한 회수의 완료 경계를 관측한다.
 * 인증은 합성 사용자 Context로 주입하며 공개 HTTP·OpenProxy·모델 경로를 시험하지 않는다.
 */
@Tag("permission-durability")
@SpringBootTest(classes = PermissionDurabilityIntegrationTest.Config.class, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/postgres?ApplicationName=permission-boundary",
    "spring.datasource.username=postgres", "spring.datasource.password=",
    "spring.datasource.hikari.maximum-pool-size=5",
    "spring.datasource.hikari.connection-init-sql=SET synchronous_commit=local",
    "permission.revocation.synchronous-enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
    "spring.flyway.locations=classpath:db/migration", "spring.flyway.enabled=true",
    "management.server.port=0", "logging.level.root=ERROR"
})
@Timeout(60)
class PermissionDurabilityIntegrationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:55439/postgres";
    private static final AtomicInteger COMMIT_WARNINGS = new AtomicInteger();
    @Autowired DocumentPermissionCommandService commands;
    @Autowired CollectionPermissionCommandService collectionCommands;
    @Autowired com.opensource.docgrid.domain.collection.repository.CollectionRepository collections;
    @Autowired com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository memberships;
    @Autowired com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade revocationFacade;
    @Autowired PermissionQueryService queries;
    @Autowired com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService primaryPermissions;
    @Autowired com.opensource.docgrid.domain.document.service.query.DocumentQueryService documentQueries;
    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    /** 시험 대상 실제 서비스만 구성하며 백그라운드 작업을 기동하지 않는다. */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.test.context.TestComponent
    @EnableAutoConfiguration
    @EntityScan("com.opensource.docgrid")
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing
    @EnableJpaRepositories("com.opensource.docgrid")
    @Import({DocumentPermissionCommandService.class, CollectionPermissionCommandService.class, UserDocumentAccessCacheService.class,
        PermissionQueryService.class, PermissionConverter.class, SyncEventWriter.class,
        com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade.class,
        com.opensource.docgrid.global.config.PermissionDurabilityConfiguration.class,
        com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService.class,
        com.opensource.docgrid.domain.document.service.query.DocumentQueryService.class,
        com.opensource.docgrid.domain.document.converter.DocumentDetailConverter.class,
        com.opensource.docgrid.domain.document.converter.DocumentStatusConverter.class,
        com.opensource.docgrid.domain.document.converter.DocumentSummaryConverter.class,
        com.opensource.docgrid.domain.document.converter.DocumentVersionHistoryConverter.class})
    static class Config {
        @Bean Clock clock() { return Clock.systemDefaultZone(); }
        // 1. Hikari가 반환 시 지우기 전에 실제 물리 COMMIT의 경고 SQLSTATE만 관측한다.
        @Bean static BeanPostProcessor commitWarningObserver() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) return bean;
                    return new DelegatingDataSource(ds) {
                        @Override public Connection getConnection() throws java.sql.SQLException {
                            Connection connection = ds.getConnection();
                            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                                    try {
                                        Object result = method.invoke(connection, args);
                                        if (method.getName().equals("commit")) {
                                            for (SQLWarning warning = connection.getWarnings(); warning != null;
                                                    warning = warning.getNextWarning()) {
                                                COMMIT_WARNINGS.incrementAndGet();
                                                event("commit-warning", "sqlstate=" + warning.getSQLState());
                                            }
                                        }
                                        return result;
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
                        }
                    };
                }
            };
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void synchronousFixture() throws Exception { configureSync("FIRST 1 (standby1, standby2)"); }
    @org.junit.jupiter.api.AfterEach
    void resetFixture() throws Exception { configureSync(""); }

    @Test
    @DisplayName("동기 COMMIT 취소는 503이며 같은 키 WAL 재주장으로 204를 재확정한다")
    void baselineCommitCancellation() throws Exception {
        Fixture f = fixture();
        COMMIT_WARNINGS.set(0);
        configureSync("missing_isolated_standby");
        try {
            CompletableFuture<Integer> request = CompletableFuture.supplyAsync(() -> revoke(f));
            int pid = awaitSyncRep();
            event("cancel", "wait_event=SyncRep");
            control("SELECT pg_cancel_backend(" + pid + ")");
            int status = request.get(10, TimeUnit.SECONDS);
            assertThat(status).isEqualTo(503);
            assertThat(COMMIT_WARNINGS.get()).isPositive();
            assertThat(permissionCount(f)).isZero();
            event("sync-cancel", "http=503 local_permission_rows=0 warnings=" + COMMIT_WARNINGS.get());
            configureSync("FIRST 1 (standby1, standby2)");
            assertThat(revoke(f)).isEqualTo(204);
            assertThat(assertionCount(f)).isEqualTo(2);
            event("sync-reassert", "retry_http=204 receipt_rows=1 wal_assertions=2");
        } finally { configureSync(""); }
    }

    @Test
    @DisplayName("회수 뒤 오래된 USER 캐시는 상세·본문·파일·새 검색의 최종 응답을 허용하지 않는다")
    void baselineStaleCache() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204);
        new JdbcTemplate(dataSource).update("UPDATE user_document_access_cache SET invalidated_at=NULL WHERE source_type='DIRECT_DOCUMENT_PERMISSION' AND source_id=?", f.permissionId());
        assertThat(permissionCount(f)).isZero();
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isFalse();
        assertThat(queries.canWriteDocument(f.targetId(), f.documentId())).isFalse();
        assertThat(queries.canAdminDocument(f.targetId(), f.documentId())).isFalse();
        var authentication = new UsernamePasswordAuthenticationToken("synthetic", "", java.util.List.of());
        authentication.setDetails(f.targetId());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var storage = mock(com.opensource.docgrid.domain.document.storage.FileStorageService.class);
            var controller = new com.opensource.docgrid.domain.document.controller.DocumentQueryController(documentQueries,
                new com.opensource.docgrid.domain.document.service.DocumentFileService(documentQueries, storage));
            var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
            for (String suffix : java.util.List.of("", "/content", "/file")) {
                var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/documents/"+f.documentId()+suffix)).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(403);
            }
            org.mockito.Mockito.verifyNoInteractions(storage);
            assertNewSearchFiltersRevokedContent(f);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        finally { SecurityContextHolder.clearContext(); }
        event("authoritative-stale-cache", "permission_rows=0 stale_cache=1 detail_403=1 content_403=1 file_403=1 search_results=0 forbidden_content=0");
    }

    @Test
    @DisplayName("응답 유실 뒤 같은 키 회수는 새 WAL로 204를 다시 확정한다")
    void baselineLostResponse() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204); // 클라이언트가 이 응답을 관측하지 못한 상황을 모델링한다.
        assertThat(revoke(f)).isEqualTo(204);
        assertThat(assertionCount(f)).isEqualTo(2);
        event("sync-response-loss", "first_response_discarded=true retry_http=204 wal_assertions=2");
    }


    @Test
    @DisplayName("동기 후보 설정이 없으면 503이며 권한과 요청 원장을 바꾸지 않는다")
    void missingSynchronousConfiguration() throws Exception {
        Fixture f = fixture();
        configureSync("");
        assertThat(revoke(f)).isEqualTo(503);
        assertThat(permissionCount(f)).isEqualTo(1);
        assertThat(assertionCount(f)).isZero();
        event("sync-not-configured", "http=503 permission_rows=1 receipt_rows=0");
    }

    @Test
    @DisplayName("같은 요청 키를 다른 권한에 사용하면 409이며 새 권한은 유지된다")
    void requestBindingConflict() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204);
        Fixture next = fixture();
        Fixture wrong = new Fixture(next.ownerId(), next.targetId(), next.documentId(), next.permissionId(), f.requestId());
        assertThat(revoke(wrong)).isEqualTo(409);
        assertThat(permissionCount(next)).isEqualTo(1);
        event("sync-key-binding", "http=409 new_permission_rows=1");
    }

    @Test
    @DisplayName("권한 없는 대상자의 회수는 403이며 요청 원장 INSERT도 롤백된다")
    void unauthorizedRollback() {
        Fixture f = fixture();
        Fixture denied = new Fixture(f.targetId(), f.targetId(), f.documentId(), f.permissionId(), f.requestId());
        assertThat(revoke(denied)).isEqualTo(403);
        assertThat(permissionCount(f)).isEqualTo(1);
        assertThat(assertionCount(f)).isZero();
        event("sync-atomic-denial", "http=403 permission_rows=1 receipt_rows=0");
    }

    @Test
    @DisplayName("같은 키의 동시 회수는 행 잠금으로 직렬화되고 요청 원장 하나로 수렴한다")
    void concurrentReassertion() throws Exception {
        Fixture f = fixture();
        var first = CompletableFuture.supplyAsync(() -> revoke(f));
        var second = CompletableFuture.supplyAsync(() -> revoke(f));
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        assertThat(permissionCount(f)).isZero();
        assertThat(assertionCount(f)).isEqualTo(2);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SHOW synchronous_commit", String.class)).isEqualTo("local");
        event("sync-concurrency", "http_204=2 receipt_rows=1 wal_assertions=2 pooled_setting=local");
    }

    @Test
    @DisplayName("동기 후보 전체 부재의 COMMIT 대기는 5초 제한 뒤 결과 불명으로 끝난다")
    void commitTimeoutIsUnconfirmed() throws Exception {
        Fixture f = fixture();
        configureSync("missing_isolated_standby");
        long start = System.nanoTime();
        var request = CompletableFuture.supplyAsync(() -> revoke(f));
        try {
            assertThat(request.get(12, TimeUnit.SECONDS)).isEqualTo(503);
            event("sync-timeout", "http=503 elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        } finally { configureSync("FIRST 1 (standby1, standby2)"); }
    }

    private long assertionCount(Fixture f) {
        return new JdbcTemplate(dataSource).queryForObject("SELECT coalesce(sum(assertion_count),0) FROM permission_revocation_requests WHERE request_id=?", Long.class, f.requestId());
    }


    private void assertNewSearchFiltersRevokedContent(Fixture f) throws Exception {
        var embedding = mock(com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService.class);
        var conversation = mock(com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService.class);
        var command = mock(com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService.class);
        var accessible = mock(com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService.class);
        var vector = mock(com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService.class);
        var results = mock(com.opensource.docgrid.domain.search.service.command.SearchResultCommandService.class);
        var model = mock(com.opensource.docgrid.domain.embedding.entity.EmbeddingModel.class);
        org.mockito.Mockito.when(command.createProcessing(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new com.opensource.docgrid.domain.search.dto.SearchAdmission(1L, 1L));
        org.mockito.Mockito.when(conversation.contextualizeRetrieval(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn("합성 질문");
        org.mockito.Mockito.when(embedding.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.opensource.docgrid.domain.embedding.dto.EmbedResult(model, new float[1024]));
        org.mockito.Mockito.when(accessible.findReadableDocumentIds(f.targetId(), null)).thenReturn(java.util.List.of(f.documentId()));
        org.mockito.Mockito.when(vector.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.List.of(new com.opensource.docgrid.domain.search.dto.VectorSearchCandidate(1L, 1L, f.documentId(), "REVOKED_CONTENT_SENTINEL", 1, "합성 문서", java.math.BigDecimal.ONE)));
        var search = new com.opensource.docgrid.domain.search.service.SearchFacade(embedding, conversation, command, accessible,
            vector, mock(com.opensource.docgrid.domain.search.service.query.HybridSearchQueryService.class),
            mock(com.opensource.docgrid.domain.search.config.HybridSearchProperties.class), primaryPermissions, results);
        var rag = mock(com.opensource.docgrid.domain.rag.service.RagFacade.class);
        org.mockito.Mockito.when(rag.enqueue(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome.stillPending());
        var controller = new com.opensource.docgrid.domain.search.controller.SearchController(search, rag,
            mock(com.opensource.docgrid.domain.search.service.query.SearchAnswerQueryService.class), conversation);
        var response = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver()).setControllerAdvice(new GlobalExceptionHandler()).build()
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/search")
                .contentType("application/json").accept("application/json").content("{\"queryText\":\"합성 질문\",\"topK\":5}"))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain("REVOKED_CONTENT_SENTINEL");
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(tree.path("data").path("results").size()).isZero();
    }

    @Test
    @DisplayName("유효한 원장과 PUBLIC·소유자 접근은 유지하고 캐시의 위조된 관리 비트는 거부한다")
    void liveGrantAndAlternateAccessRemainValid() {
        Fixture f = fixture();
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isTrue();
        new JdbcTemplate(dataSource).update("UPDATE user_document_access_cache SET can_admin=true WHERE source_id=? AND source_type='DIRECT_DOCUMENT_PERMISSION'", f.permissionId());
        assertThat(queries.canAdminDocument(f.targetId(), f.documentId())).isFalse();
        assertThat(revoke(f)).isEqualTo(204);
        assertThat(primaryPermissions.canReadDocument(f.ownerId(), f.documentId())).isTrue();
        new JdbcTemplate(dataSource).update("UPDATE documents SET visibility='PUBLIC' WHERE id=?", f.documentId());
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isTrue();
        event("authoritative-valid-paths", "valid_user_read=true forged_admin=false owner=true public=true");
    }

    @Test
    @DisplayName("별도 컬렉션 USER 권한은 유지하지만 만료·문서 연결 제거 뒤에는 오래된 캐시를 거부한다")
    void collectionProvenanceRemainsLive() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204);
        Long collectionId = new TransactionTemplate(transactionManager).execute(status -> {
            var owner = users.findById(f.ownerId()).orElseThrow();
            var collection = collections.save(com.opensource.docgrid.domain.collection.entity.DocumentCollection.builder()
                .owner(owner).name("합성 컬렉션").visibility(VisibilityType.PRIVATE).build());
            collections.insertClosureForNewCollection(collection.getId(), null);
            memberships.save(com.opensource.docgrid.domain.collection.entity.CollectionDocument.builder()
                .collection(collection).document(documents.findById(f.documentId()).orElseThrow())
                .addedBy(owner).addedAt(LocalDateTime.now()).build());
            return collection.getId();
        });
        var grant = collectionCommands.grantPermission(collectionId, f.ownerId(), new GrantPermissionRequest(
            PermissionTargetType.USER, f.targetId(), null, null, PermissionType.READ, null));
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isTrue();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE collection_permissions SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE id=?", grant.permissionId());
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isFalse();
        jdbc.update("UPDATE collection_permissions SET expires_at=NULL WHERE id=?", grant.permissionId());
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isTrue();
        jdbc.update("DELETE FROM collection_documents WHERE collection_id=? AND document_id=?", collectionId, f.documentId());
        assertThat(primaryPermissions.canReadDocument(f.targetId(), f.documentId())).isFalse();
        event("authoritative-collection", "live_collection=true expired=false detached=false");
    }

    private Fixture fixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User owner = users.save(User.builder().email("owner-" + java.util.UUID.randomUUID() + "@example.invalid")
                .passwordHash("!disabled!").name("합성 소유자").status(UserStatus.ACTIVE).build());
            User target = users.save(User.builder().email("target-" + java.util.UUID.randomUUID() + "@example.invalid")
                .passwordHash("!disabled!").name("합성 대상자").status(UserStatus.ACTIVE).build());
            Document document = documents.save(Document.builder().owner(owner).title("격리 합성 권한 문서")
                .documentType(DocumentType.TXT).sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED).visibility(VisibilityType.PRIVATE).build());
            var grant = commands.grantPermission(document.getId(), owner.getId(),
                new GrantPermissionRequest(PermissionTargetType.USER, target.getId(), null, null, PermissionType.READ, null));
            return new Fixture(owner.getId(), target.getId(), document.getId(), grant.permissionId(), java.util.UUID.randomUUID());
        });
    }

    private int revoke(Fixture f) {
        var authentication = new UsernamePasswordAuthenticationToken("synthetic", "", java.util.List.of());
        authentication.setDetails(f.ownerId());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            PermissionController controller = new PermissionController(mock(CollectionPermissionCommandService.class),
                commands, queries, mock(PermissionTargetUserQueryService.class), revocationFacade);
            return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(delete("/permissions/documents/{d}/{p}", f.documentId(), f.permissionId()).header("Idempotency-Key", f.requestId().toString()))
                .andReturn().getResponse().getStatus();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private long permissionCount(Fixture f) {
        return new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM document_permissions WHERE id=?", Long.class, f.permissionId());
    }
    private int awaitSyncRep() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection c = DriverManager.getConnection(URL, "postgres", ""); var s = c.createStatement();
                    var rows = s.executeQuery("SELECT pid FROM pg_stat_activity WHERE application_name='permission-boundary' AND wait_event='SyncRep'")) {
                if (rows.next()) return rows.getInt(1);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("SyncRep was not observed");
    }
    private static void configureSync(String names) throws Exception {
        control("ALTER SYSTEM SET synchronous_standby_names='" + names + "'");
        control("SELECT pg_reload_conf()");
        Thread.sleep(250);
    }
    private static void control(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "postgres", ""); var s = c.createStatement()) { s.execute(sql); }
    }
    private static void event(String run, String detail) {
        System.out.println("EVIDENCE " + OffsetDateTime.now() + " run=" + run + " " + detail);
    }
    /** 합성 fixture의 ID만 보관하며 인증 정보는 보관하지 않는다. */
    private record Fixture(Long ownerId, Long targetId, Long documentId, Long permissionId, java.util.UUID requestId) { }
}
