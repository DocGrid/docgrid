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
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
    "spring.flyway.locations=classpath:db/migration", "spring.flyway.enabled=true",
    "management.server.port=0", "logging.level.root=ERROR"
})
@Timeout(60)
class PermissionDurabilityIntegrationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:55439/postgres";
    private static final AtomicInteger COMMIT_WARNINGS = new AtomicInteger();
    @Autowired DocumentPermissionCommandService commands;
    @Autowired PermissionQueryService queries;
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
    @Import({DocumentPermissionCommandService.class, UserDocumentAccessCacheService.class,
        PermissionQueryService.class, PermissionConverter.class, SyncEventWriter.class})
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

    @Test
    @DisplayName("수정 전: 동기 COMMIT 취소 경고가 있어도 회수 MVC가 204를 반환한다")
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
            assertThat(status).isEqualTo(204);
            assertThat(COMMIT_WARNINGS.get()).isPositive();
            assertThat(permissionCount(f)).isZero();
            event("baseline-commit-cancel", "http=204 local_permission_rows=0 warnings=" + COMMIT_WARNINGS.get());
        } finally { configureSync(""); }
    }

    @Test
    @DisplayName("수정 전: 원장은 회수됐어도 오래된 USER 캐시는 최종 읽기 인가를 허용한다")
    void baselineStaleCache() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204);
        new JdbcTemplate(dataSource).update("UPDATE user_document_access_cache SET invalidated_at=NULL WHERE source_type='DIRECT_DOCUMENT_PERMISSION' AND source_id=?", f.permissionId());
        assertThat(permissionCount(f)).isZero();
        assertThat(queries.canReadDocument(f.targetId(), f.documentId())).isTrue();
        event("baseline-stale-cache", "permission_rows=0 stale_cache=1 final_read_allowed=true");
    }

    @Test
    @DisplayName("수정 전: 완료 응답을 잃은 회수의 동일 대상 재요청은 404이며 동기 재확정 수단이 없다")
    void baselineLostResponse() {
        Fixture f = fixture();
        assertThat(revoke(f)).isEqualTo(204); // 클라이언트가 이 응답을 관측하지 못한 상황을 모델링한다.
        assertThat(revoke(f)).isEqualTo(404);
        event("baseline-response-loss", "first_response_discarded=true retry_http=404");
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
            return new Fixture(owner.getId(), target.getId(), document.getId(), grant.permissionId());
        });
    }

    private int revoke(Fixture f) {
        var authentication = new UsernamePasswordAuthenticationToken("synthetic", "", java.util.List.of());
        authentication.setDetails(f.ownerId());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            PermissionController controller = new PermissionController(mock(CollectionPermissionCommandService.class),
                commands, queries, mock(PermissionTargetUserQueryService.class));
            return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(delete("/permissions/documents/{d}/{p}", f.documentId(), f.permissionId()))
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
    private record Fixture(Long ownerId, Long targetId, Long documentId, Long permissionId) { }
}
