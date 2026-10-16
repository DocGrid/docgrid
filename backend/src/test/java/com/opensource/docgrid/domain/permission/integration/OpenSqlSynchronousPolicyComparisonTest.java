package com.opensource.docgrid.domain.permission.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.*;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.controller.PermissionController;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.enums.*;
import com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade;
import com.opensource.docgrid.domain.permission.service.command.*;
import com.opensource.docgrid.domain.permission.service.query.*;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.GlobalExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 실제 격리 OpenSQL·OpenProxy에서 같은 MVC·JPA 회수 부하의 성공 보존·재확정·인가를 계측한다.
 * 장애는 외부 제어기가 시험 컨테이너에만 주입하고 요청 원장은 DB 밖에 즉시 기록한다.
 */
@Tag("permission-ha")
@SpringBootTest(classes = PermissionDurabilityIntegrationTest.Config.class, properties = {
    "spring.datasource.url=${PERMISSION_HA_JDBC_URL}", "spring.datasource.username=postgres",
    "spring.datasource.password=${PERMISSION_HA_PASSWORD}", "spring.datasource.hikari.maximum-pool-size=5",
    "spring.datasource.hikari.connection-timeout=5000", "spring.datasource.hikari.validation-timeout=2000",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
    "spring.flyway.locations=classpath:db/migration", "spring.flyway.enabled=true",
    "spring.flyway.url=${PERMISSION_HA_MIGRATION_JDBC_URL}", "spring.flyway.user=postgres",
    "spring.flyway.password=${PERMISSION_HA_PASSWORD}",
    "permission.revocation.synchronous-enabled=true", "management.server.port=0", "logging.level.root=OFF"
})
@Timeout(240)
class OpenSqlSynchronousPolicyComparisonTest {
    private static final int SAMPLE_COUNT = 48;
    @Autowired DocumentPermissionCommandService commands;
    @Autowired DocumentPermissionRevocationFacade facade;
    @Autowired PermissionQueryService queries;
    @Autowired PrimaryDocumentPermissionQueryService primary;
    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger forbidden = new AtomicInteger();
    private final AtomicInteger authorizationUnavailable = new AtomicInteger();
    private final List<Result> results = Collections.synchronizedList(new ArrayList<>());
    private MockMvc mvc;
    private Path directory;

    @Test
    @DisplayName("동일 회수 부하 중 장애가 있어도 확정 요청이 보존되고 불명 요청은 같은 키로 수렴한다")
    void compareAcknowledgedRevocations() throws Exception {
        directory = Path.of(System.getenv("PERMISSION_HA_RUN_DIR"));
        Files.createDirectories(directory);
        var controller = new PermissionController(mock(CollectionPermissionCommandService.class), commands,
            queries, mock(PermissionTargetUserQueryService.class), facade);
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        event("fixture-start", "samples=" + SAMPLE_COUNT);
        List<Fixture> fixtures = seed();
        event("fixture-ready", "samples=" + fixtures.size());
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxPending = new AtomicInteger();
        var monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            maxActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
            maxPending.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
        }, 0, 100, TimeUnit.MILLISECONDS);
        var workers = Executors.newFixedThreadPool(2);
        try {
            // 1. 같은 Binary·표본·동시성으로 실행하며 매 응답을 DB 밖에 즉시 보존한다.
            var futures = new ArrayList<Future<?>>();
            for (Fixture f : fixtures) futures.add(workers.submit(() -> execute(f, false)));
            for (Future<?> future : futures) future.get(150, TimeUnit.SECONDS);
            // 2. 외부 장애 제어기의 복구 관측이 끝나기 전 결과를 확정하지 않는다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!Files.exists(directory.resolve("fault.done")) && System.nanoTime() < deadline) Thread.sleep(100);
            assertThat(Files.exists(directory.resolve("fault.done"))).isTrue();
            // 3. 결과 불명은 원장을 읽기만 하지 않고 같은 키 WAL 재주장으로 해소한다.
            List<Result> initial = List.copyOf(results);
            for (Result result : initial) {
                if (result.status()!=204) {
                    for (int retry=0;retry<4;retry++) {
                        if (execute(result.fixture(), true).status()==204) break;
                        Thread.sleep(500);
                    }
                }
            }
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int acknowledgedPreserved = 0;
            for (Fixture f : fixtures) {
                long receipt = jdbc.queryForObject("SELECT count(*) FROM permission_revocation_requests WHERE request_id=?", Long.class, f.key());
                long permission = jdbc.queryForObject("SELECT count(*) FROM document_permissions WHERE id=?", Long.class, f.permission());
                assertThat(receipt).isEqualTo(1);
                assertThat(permission).isZero();
                assertThat(primary.canReadDocument(f.target(), f.document())).isFalse();
                acknowledgedPreserved++;
            }
            assertThat(forbidden.get()).isZero();
            long accepted = initial.stream().filter(r -> r.status()==204).count();
            long unknown = initial.size()-accepted;
            double[] latency = initial.stream().mapToDouble(Result::latencyMs).sorted().toArray();
            String summary="initial_requests="+initial.size()+" initial_204="+accepted+" initial_unknown="+unknown
                +" final_receipts="+acknowledgedPreserved+" final_permissions=0 forbidden_content="+forbidden.get()
                +" auth_unavailable="+authorizationUnavailable.get()+" p50_ms="+percentile(latency,.50)+" p95_ms="+percentile(latency,.95)
                +" hikari_max_active="+maxActive.get()+" hikari_max_pending="+maxPending.get();
            Files.writeString(directory.resolve("summary.txt"), summary+"\n", StandardOpenOption.CREATE_NEW);
            event("summary",summary);
        } finally {
            workers.shutdownNow();monitor.shutdownNow();
        }
    }

    private List<Fixture> seed() {
        return new TransactionTemplate(manager).execute(status -> {
            User owner=users.save(User.builder().email(UUID.randomUUID()+"@example.invalid").passwordHash("!disabled!").name("합성 소유자").status(UserStatus.ACTIVE).build());
            User target=users.save(User.builder().email(UUID.randomUUID()+"@example.invalid").passwordHash("!disabled!").name("합성 대상자").status(UserStatus.ACTIVE).build());
            List<Fixture> fixtures=new ArrayList<>();
            for(int i=0;i<SAMPLE_COUNT;i++) {
                Document d=documents.save(Document.builder().owner(owner).title("격리 권한 합성 문서 "+i).documentType(DocumentType.TXT)
                    .sourceType(DocumentSourceType.UPLOAD).status(DocumentStatus.INDEXED).visibility(VisibilityType.PRIVATE).build());
                var p=commands.grantPermission(d.getId(),owner.getId(),new GrantPermissionRequest(PermissionTargetType.USER,target.getId(),null,null,PermissionType.READ,null));
                fixtures.add(new Fixture(i,owner.getId(),target.getId(),d.getId(),p.permissionId(),UUID.randomUUID()));
            }
            return fixtures;
        });
    }

    private Result execute(Fixture f, boolean retry) {
        var authentication=new UsernamePasswordAuthenticationToken("synthetic", "", List.of());
        authentication.setDetails(f.owner());SecurityContextHolder.getContext().setAuthentication(authentication);
        long start=System.nanoTime();int code;
        try {
            code=mvc.perform(delete("/permissions/documents/{d}/{p}",f.document(),f.permission())
                .header("Idempotency-Key",f.key().toString()).accept("application/json")).andReturn().getResponse().getStatus();
        } catch(Exception exception) {code=599;}
        finally {SecurityContextHolder.clearContext();}
        double elapsed=(System.nanoTime()-start)/1_000_000.0;
        Result result=new Result(f,code,elapsed);
        results.add(result);
        // 외부 원장은 합성 요청 키·논리 표본 번호·상태·시간만 기록한다.
        synchronized(this) {
            try { Files.writeString(directory.resolve("requests.jsonl"),
                "{\"at\":\""+OffsetDateTime.now()+"\",\"sample\":"+f.ordinal()+",\"key\":\""+f.key()+"\",\"retry\":"+retry+",\"status\":"+code+",\"latency_ms\":"+elapsed+"}\n",
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                if(!retry && completed.incrementAndGet()==4) Files.writeString(directory.resolve("ready"),"first_four_requests_completed\n",StandardOpenOption.CREATE_NEW);
            } catch(Exception exception) {throw new IllegalStateException("External ledger write failed",exception);}
        }
        if(code==204) {
            try {if(primary.canReadDocument(f.target(),f.document()))forbidden.incrementAndGet();}
            catch(Exception exception) {authorizationUnavailable.incrementAndGet();}
        }
        return result;
    }
    private static double percentile(double[] values,double quantile) {
        return values[Math.max(0,(int)Math.ceil(values.length*quantile)-1)];
    }
    private static void event(String phase,String detail) {
        System.out.println("EVIDENCE "+OffsetDateTime.now()+" mode="+System.getenv("PERMISSION_HA_MODE")
            +" scenario="+System.getenv("PERMISSION_HA_SCENARIO")+" phase="+phase+" "+detail);
    }
    /** 합성 실행에 필요한 ID만 보관하며 실제 계정·인프라 주소는 포함하지 않는다. */
    private record Fixture(int ordinal,Long owner,Long target,Long document,Long permission,UUID key) {}
    /** 한 번의 실제 MVC 응답과 지연을 외부 원장·최종 DB 대조에 연결한다. */
    private record Result(Fixture fixture,int status,double latencyMs) {}
}
