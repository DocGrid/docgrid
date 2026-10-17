package com.opensource.docgrid.domain.permission.integration;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.permission.controller.PermissionController;
import com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade;
import com.opensource.docgrid.domain.permission.service.SynchronousCommitTransactionManager;
import com.opensource.docgrid.domain.permission.service.command.*;
import com.opensource.docgrid.domain.permission.service.query.*;
import com.opensource.docgrid.global.exception.GlobalExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 실제 격리 OpenSQL·공유 Hikari에서 회수 on을 고정하고 합성 독립 쓰기의 on/local만 비교한다.
 * Controller 호출은 MVC이며 공개 HTTP·인증·실제 업무 local 도입을 검증하지 않는다.
 */
@Tag("integration")
@Tag("permission-mixed")
@SpringBootTest(classes=PermissionDurabilityIntegrationTest.Config.class,properties={
    "spring.datasource.url=${PERMISSION_HA_JDBC_URL}","spring.datasource.username=postgres",
    "spring.datasource.password=${PERMISSION_HA_PASSWORD}","spring.datasource.hikari.maximum-pool-size=5",
    "spring.datasource.hikari.connection-timeout=5000","spring.datasource.hikari.validation-timeout=2000",
    "spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.open-in-view=false",
    "permission.revocation.synchronous-enabled=true","logging.level.root=OFF","management.server.port=0"
})
@Timeout(1800)
class PermissionMixedCommitIntegrationTest {
    private static final int EACH_KIND=24;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired DocumentPermissionCommandService commands;
    @Autowired DocumentPermissionRevocationFacade facade;
    @Autowired PermissionQueryService queries;
    private final ObjectMapper json=new ObjectMapper();
    private Path root;
    private Path directory;
    private final List<Result> responses=Collections.synchronizedList(new ArrayList<>());
    private MockMvc mvc;

    @Test
    @DisplayName("같은 클러스터·풀·부하에서 독립 쓰기 on/local의 대기와 성공 원장 보존을 비교한다")
    void compareCommitPolicies() throws Exception {
        root=Path.of(System.getenv("PERMISSION_HA_RUN_DIR"));Files.createDirectories(root);
        // 순서를 명시하며 Primary 상실 회차는 local을 먼저 실행해 항상 같은 순서의 편향을 줄인다.
        for(CaseSpec spec:List.of(new CaseSpec("on","ack-loss"),new CaseSpec("local","ack-loss"),new CaseSpec("local","primary-loss"),new CaseSpec("on","primary-loss"))) {
            directory=root.resolve(spec.id());Files.createDirectories(directory);responses.clear();
            event("case-start",Map.of("mode",spec.mode(),"scenario",spec.scenario(),"samples_each_kind",EACH_KIND,"concurrency",8,"pool_size",5));
            Files.writeString(directory.resolve("case.waiting"),"isolated_reset_required\n",StandardOpenOption.CREATE_NEW);
            await(directory.resolve("environment.ready"),180);
            HikariDataSource pool=dataSource.unwrap(HikariDataSource.class);
            pool.getHikariPoolMXBean().softEvictConnections();
            List<Fixture> fixtures=seed();
            var independent=new IndependentWriteController(dataSource,manager,spec.mode(),spec.id());
            mvc=MockMvcBuilders.standaloneSetup(new PermissionController(mock(CollectionPermissionCommandService.class),commands,queries,mock(PermissionTargetUserQueryService.class),facade),independent)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver()).setControllerAdvice(new GlobalExceptionHandler()).build();
            AtomicInteger completed=new AtomicInteger(),maxActive=new AtomicInteger(),maxPending=new AtomicInteger();
            AtomicReference<Exception> captureFailure=new AtomicReference<>();
            var monitor=Executors.newSingleThreadScheduledExecutor();
            monitor.scheduleAtFixedRate(()->{
                int active=pool.getHikariPoolMXBean().getActiveConnections(),pending=pool.getHikariPoolMXBean().getThreadsAwaitingConnection();
                maxActive.accumulateAndGet(active,Math::max);maxPending.accumulateAndGet(pending,Math::max);
                try{append("pool.jsonl",Map.of("active",active,"pending",pending));}catch(Exception e){captureFailure.compareAndSet(null,e);}
            },0,100,TimeUnit.MILLISECONDS);
            var workers=Executors.newFixedThreadPool(8);
            try {
                // 1. 같은 순서·개수의 별도 트랜잭션을 공유 풀에 투입한다.
                var futures=new ArrayList<Future<?>>();
                for(Fixture fixture:fixtures)for(String kind:List.of("revoke","independent"))futures.add(workers.submit(()->{
                    // 최초 4건 뒤 나머지 44건은 실제 장애가 확인된 같은 경계에서 시작한다.
                    if(fixture.ordinal()>=2)try{await(directory.resolve("fault.active"),150);}catch(Exception e){throw new IllegalStateException("Fault barrier failed");}
                    Result initialResponse=execute(fixture,kind,false,spec.mode());
                    if(fixture.ordinal()<2)require(initialResponse.status()==204,"baseline_response_not_confirmed");
                    if(completed.incrementAndGet()==4)try{Files.writeString(directory.resolve("ready"),"first_four_responses\n",StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new IllegalStateException("Ready marker failed");}
                }));
                for(Future<?> future:futures)future.get(150,TimeUnit.SECONDS);
                await(directory.resolve("fault.done"),150);
                List<Result> initial=List.copyOf(responses);
                // 2. 결과 불명 재시도로 최초 성공의 유실을 가리지 않도록 실제 키를 먼저 감사한다.
                Map<String,Set<UUID>> before=audit(fixtures);
                var measured=new LinkedHashMap<String,Object>();measured.put("mode",spec.mode());measured.put("scenario",spec.scenario());
                for(String kind:List.of("revoke","independent")) {
                    List<Result> group=initial.stream().filter(r->r.kind().equals(kind)).toList();
                    long acknowledged=group.stream().filter(r->r.status()==204).count();
                    long missing=group.stream().filter(r->r.status()==204&&!before.get(kind).contains(r.key())).count();
                    double[] times=group.stream().mapToDouble(Result::latencyMs).sorted().toArray();
                    measured.put(kind,Map.of("initial_requests",group.size(),"initial_204",acknowledged,"initial_unconfirmed",group.size()-acknowledged,"acknowledged_missing_before_retry",missing,"p50_ms",percentile(times,.5),"p95_ms",percentile(times,.95)));
                    
                }
                event("audit-before-retry",measured);
                for(String kind:List.of("revoke","independent")) {
                    long missing=initial.stream().filter(r->r.kind().equals(kind)&&r.status()==204&&!before.get(kind).contains(r.key())).count();
                    require(kind.equals("independent")&&spec.mode().equals("local")||missing==0,"acknowledged_durable_write_missing");
                }
                // 3. 최초 성공 키는 재생성하지 않고 결과 불명만 같은 키로 재확정한다.
                for(Result result:initial)if(result.status()!=204) {
                    for(int attempt=0;attempt<4;attempt++) {
                        if(execute(result.fixture(),result.kind(),true,spec.mode()).status()==204)break;
                        Thread.sleep(500);
                    }
                }
                Map<String,Set<UUID>> after=audit(fixtures);
                require(after.get("revoke").size()==EACH_KIND,"revocation_not_resolved");
                for(Result result:initial)if(result.status()!=204) {
                    require(after.get(result.kind()).contains(result.key()),"unconfirmed_key_not_resolved");
                    require(responses.stream().anyMatch(r->r.key().equals(result.key())&&r.status()==204),"retry_never_confirmed");
                }
                require(captureFailure.get()==null,"pool_capture_failed");
                new TransactionTemplate(manager).execute(status->{
                    JdbcTemplate jdbc=new JdbcTemplate(dataSource);
                    String marks=String.join(",",Collections.nCopies(fixtures.size(),"?"));
                    require(jdbc.queryForObject("SELECT count(*) FROM document_permissions WHERE id IN ("+marks+")",Long.class,fixtures.stream().map(Fixture::permission).toArray())==0,"permission_resurrected");
                    require("on".equals(jdbc.queryForObject("SELECT current_setting('synchronous_commit')",String.class)),"local_setting_leaked");return null;
                });
                measured.put("final_revocation_keys",after.get("revoke").size());measured.put("final_independent_keys",after.get("independent").size());
                measured.put("hikari_max_active",maxActive.get());measured.put("hikari_max_pending",maxPending.get());
                measured.put("retry_requests",responses.size()-initial.size());measured.put("pool_default_after_case","on");
                Files.writeString(directory.resolve("summary.json"),json.writeValueAsString(measured)+"\n",StandardOpenOption.CREATE_NEW);
                event("case-summary",measured);Files.writeString(directory.resolve("case.done"),"case_finished\n",StandardOpenOption.CREATE_NEW);
            } finally {workers.shutdownNow();monitor.shutdownNow();monitor.awaitTermination(2,TimeUnit.SECONDS);}
        }
    }

    private List<Fixture> seed() throws Exception {
        // 합성 데이터는 측정 전에 직접 Primary의 한 트랜잭션으로 준비한다.
        try(var c=DriverManager.getConnection(System.getenv("PERMISSION_HA_MIGRATION_JDBC_URL"),"postgres",System.getenv("PERMISSION_HA_PASSWORD"))) {
            c.setAutoCommit(false);
            try(var s=c.createStatement();var r=s.executeQuery("SELECT NOT pg_is_in_recovery() AND current_setting('port')='15439' AND current_setting('synchronous_standby_names')<>''")){r.next();require(r.getBoolean(1),"isolated_sync_primary_required");}
            try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS permission_independent_probe(request_id UUID PRIMARY KEY, run_id TEXT NOT NULL, commit_mode TEXT NOT NULL, assertion_count INT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())");}
            Long owner,target;
            try(var s=c.prepareStatement("INSERT INTO users(email,password_hash,name,status,created_at,updated_at) VALUES(?, '!disabled!', ?, 'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id")) {
                s.setString(1,"mixed-owner-"+UUID.randomUUID()+"@example.invalid");s.setString(2,"합성 소유자");try(var r=s.executeQuery()){r.next();owner=r.getLong(1);}
                s.setString(1,"mixed-target-"+UUID.randomUUID()+"@example.invalid");s.setString(2,"합성 대상자");try(var r=s.executeQuery()){r.next();target=r.getLong(1);}
            }
            List<Fixture> fixtures=new ArrayList<>();
            try(var s=c.prepareStatement("WITH docs AS (INSERT INTO documents(owner_user_id,title,document_type,source_type,status,visibility,created_at,updated_at) SELECT ?, '합성 독립 정책 비교 문서','TXT','UPLOAD','INDEXED','PRIVATE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,?) RETURNING id) INSERT INTO document_permissions(document_id,target_type,user_id,permission_type,can_read,can_write,can_admin,granted_by,granted_at,created_at,updated_at) SELECT id,'USER',?,'READ',true,false,false,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM docs RETURNING id,document_id")) {
                s.setLong(1,owner);s.setInt(2,EACH_KIND);s.setLong(3,target);s.setLong(4,owner);
                try(var r=s.executeQuery()){while(r.next())fixtures.add(new Fixture(fixtures.size(),owner,r.getLong(2),r.getLong(1),UUID.randomUUID(),UUID.randomUUID()));}
            }
            try(var s=c.prepareStatement("INSERT INTO user_document_access_cache(user_id,document_id,can_read,can_write,can_admin,source_type,source_id,computed_at,created_at,updated_at) SELECT user_id,document_id,true,false,false,'DIRECT_DOCUMENT_PERMISSION',id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM document_permissions WHERE user_id=?")){s.setLong(1,target);s.executeUpdate();}
            c.commit();return fixtures;
        }
    }

    private Result execute(Fixture f,String kind,boolean retry,String mode) {
        UUID key=kind.equals("revoke")?f.revokeKey():f.independentKey();
        var authentication=new UsernamePasswordAuthenticationToken("synthetic","",List.of());authentication.setDetails(f.owner());SecurityContextHolder.getContext().setAuthentication(authentication);
        long start=System.nanoTime();int code;String observed="";
        try {
            var request=kind.equals("revoke")?delete("/permissions/documents/{d}/{p}",f.document(),f.permission()):post("/isolated-independent-write");
            var response=mvc.perform(request.header("Idempotency-Key",key.toString()).accept("application/json")).andReturn().getResponse();
            code=response.getStatus();if(kind.equals("independent")&&code==204)observed=response.getHeader("X-Observed-Commit-Mode");
        }catch(Exception e){code=599;}finally{SecurityContextHolder.clearContext();}
        double latency=(System.nanoTime()-start)/1_000_000.0;Result result=new Result(f,kind,key,code,latency);responses.add(result);
        try{append("requests.jsonl",Map.of("sample",f.ordinal(),"kind",kind,"key",key.toString(),"retry",retry,"status",code,"latency_ms",latency,"configured_mode",mode,"observed_mode",observed==null?"":observed));}
        catch(Exception e){throw new IllegalStateException("External ledger failed");}
        if(kind.equals("independent")&&code==204)require(mode.equals(observed),"independent_mode_not_applied");
        return result;
    }

    private Map<String,Set<UUID>> audit(List<Fixture> fixtures) {
        return new TransactionTemplate(manager).execute(status->{
            JdbcTemplate jdbc=new JdbcTemplate(dataSource);
            require(Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_is_in_recovery()",Boolean.class)),"audit_not_primary");
            Map<String,Set<UUID>> result=new HashMap<>();
            for(String kind:List.of("revoke","independent")) {
                String table=kind.equals("revoke")?"permission_revocation_requests":"permission_independent_probe";
                Object[] keys=fixtures.stream().map(f->kind.equals("revoke")?f.revokeKey():f.independentKey()).toArray();
                String marks=String.join(",",Collections.nCopies(keys.length,"?"));
                var ids=jdbc.query("SELECT request_id FROM "+table+" WHERE request_id IN ("+marks+")",(r,n)->r.getObject(1,UUID.class),keys);
                result.put(kind,new HashSet<>(ids));
            }
            return result;
        });
    }

    /** 시험 전용 독립 쓰기 API. 회수 원장과 FK·행 잠금을 공유하지 않으며 같은 풀과 COMMIT 판정을 사용한다. */
    @TestComponent
    @RestController
    static class IndependentWriteController {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transaction;
        private final String mode,run;
        IndependentWriteController(DataSource source,PlatformTransactionManager manager,String mode,String run) {
            require(List.of("on","local").contains(mode),"invalid_commit_mode");this.jdbc=new JdbcTemplate(source);this.transaction=new TransactionTemplate(manager);this.mode=mode;this.run=run;
        }
        @PostMapping("/isolated-independent-write")
        ResponseEntity<Void> write(@RequestHeader("Idempotency-Key") UUID key) {
            try {
                String observed=SynchronousCommitTransactionManager.guarded(()->transaction.execute(status->{
                    jdbc.execute("SET LOCAL synchronous_commit="+mode);
                    String setting=jdbc.queryForObject("SELECT current_setting('synchronous_commit')",String.class);
                    jdbc.update("INSERT INTO permission_independent_probe(request_id,run_id,commit_mode) VALUES(?,?,?) ON CONFLICT DO NOTHING",key,run,mode);
                    // 같은 키의 불명 결과도 새 WAL로 재주장해 변경 0행의 모호한 재시도를 피한다.
                    jdbc.update("UPDATE permission_independent_probe SET assertion_count=assertion_count+1,updated_at=clock_timestamp() WHERE request_id=?",key);return setting;
                }));
                return ResponseEntity.noContent().header("X-Observed-Commit-Mode",observed).build();
            }catch(org.springframework.transaction.TransactionException|org.springframework.dao.DataAccessException e){return ResponseEntity.status(503).build();}
        }
    }
    private void await(Path marker,int seconds) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(!Files.exists(marker)&&System.nanoTime()<end)Thread.sleep(100);require(Files.exists(marker),"external_control_marker_missing");
    }
    private synchronized void append(String name,Map<String,?> detail) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("at_kst",OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());row.putAll(detail);
        Files.writeString(directory.resolve(name),json.writeValueAsString(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private void event(String event,Map<String,?> detail) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>(detail);row.put("event",event);append("events.jsonl",row);System.out.println("EVIDENCE "+json.writeValueAsString(row));
    }
    private static double percentile(double[] values,double q){return values[Math.max(0,(int)Math.ceil(values.length*q)-1)];}
    private static void require(boolean valid,String category){if(!valid)throw new AssertionError(category);}
    /** 두 종류의 합성 요청 키와 회수 대상 ID를 연결한다. */
    private record Fixture(int ordinal,Long owner,Long document,Long permission,UUID revokeKey,UUID independentKey) {}
    /** 실제 MVC 한 번의 완료 상태와 대기 시간이다. */
    private record Result(Fixture fixture,String kind,UUID key,int status,double latencyMs) {}
    /** 한 변수 비교의 고정 조건이다. */
    private record CaseSpec(String mode,String scenario){String id(){return mode+"-"+scenario;}}
}
