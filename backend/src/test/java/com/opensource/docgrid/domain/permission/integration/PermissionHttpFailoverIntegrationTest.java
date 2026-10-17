package com.opensource.docgrid.domain.permission.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.auth.controller.AuthController;
import com.opensource.docgrid.domain.auth.jwt.*;
import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.auth.service.command.AuthCommandService;
import com.opensource.docgrid.domain.auth.service.query.*;
import com.opensource.docgrid.domain.document.controller.DocumentQueryController;
import com.opensource.docgrid.domain.document.entity.*;
import com.opensource.docgrid.domain.document.enums.*;
import com.opensource.docgrid.domain.document.repository.*;
import com.opensource.docgrid.domain.document.service.DocumentFileService;
import com.opensource.docgrid.domain.document.storage.*;
import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.entity.*;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.repository.*;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.permission.controller.PermissionController;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.enums.*;
import com.opensource.docgrid.domain.permission.service.command.DocumentPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.query.PermissionTargetUserQueryService;
import com.opensource.docgrid.domain.rag.dto.*;
import com.opensource.docgrid.domain.rag.service.RagFacade;
import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.controller.SearchController;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.domain.search.service.command.*;
import com.opensource.docgrid.domain.search.service.query.*;
import com.opensource.docgrid.domain.user.entity.*;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.*;
import com.opensource.docgrid.global.config.*;
import com.opensource.docgrid.global.exception.GlobalExceptionHandler;
import com.opensource.docgrid.global.exception.RestAccessDeniedHandler;
import com.opensource.docgrid.global.exception.RestAuthenticationEntryPoint;

/**
 * 루프백 실제 HTTP·로그인·JWT·Valkey·원장을 격리 OpenSQL 승격 전후로 연결한다.
 * 파일·검색 후보·답변만 합성이며 공개 서비스·실제 모델 품질·과거 대화는 검증하지 않는다.
 */
@Tag("integration")
@Tag("permission-http-ha")
@SpringBootTest(classes = PermissionHttpFailoverIntegrationTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "search.hybrid.enabled=false", "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=OFF",
    "spring.datasource.url=${PERMISSION_HA_JDBC_URL}", "spring.datasource.username=postgres",
    "spring.datasource.password=${PERMISSION_HA_PASSWORD}", "spring.datasource.hikari.maximum-pool-size=5",
    "spring.datasource.hikari.connection-timeout=5000", "spring.datasource.hikari.validation-timeout=2000",
    "spring.flyway.url=${PERMISSION_HA_MIGRATION_JDBC_URL}", "spring.flyway.user=postgres",
    "spring.flyway.password=${PERMISSION_HA_PASSWORD}", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false", "permission.revocation.synchronous-enabled=true",
    "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=16389",
    "jwt.secret=${PERMISSION_HTTP_JWT_SECRET}", "jwt.expiration=600", "management.server.port=0"
})
@Timeout(600)
class PermissionHttpFailoverIntegrationTest {
    private static final String REVOKED = "REVOKED_HTTP_SENTINEL_542";
    private static final String ALLOWED = "ALLOWED_HTTP_SENTINEL_542";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserRoleRepository userRoles;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentChunkRepository chunks;
    @Autowired FileObjectRepository files;
    @Autowired EmbeddingRepository embeddings;
    @Autowired EmbeddingModelRepository models;
    @Autowired DocumentPermissionCommandService permissions;
    @Autowired FixtureStorage storage;
    @Autowired HybridSearchProperties searchProperties;
    @MockitoBean QueryEmbeddingService queryEmbedding;
    @MockitoBean VectorSearchQueryService candidates;
    @MockitoBean HybridSearchQueryService hybrid;
    @MockitoBean RagFacade rag;
    @MockitoBean SearchAnswerQueryService savedAnswer;
    @MockitoBean McpAccessTokenCommandService mcp;
    @MockitoBean AuthQueryService me;
    @MockitoBean PermissionTargetUserQueryService targetUsers;
    private Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final UUID requestKey = UUID.randomUUID();

    /** 실제 인증·HTTP·권한·검색 저장만 기동하며 백그라운드 Worker는 실행하지 않는다. */
    @Configuration
    @TestComponent
    @EnableAutoConfiguration
    @Import({PermissionDurabilityIntegrationTest.Config.class, SecurityConfig.class, CorsConfig.class,
        WebMvcConfig.class, CurrentUserArgumentResolver.class, JwtProvider.class, RoleAuthorityService.class,
        TokenBlacklistService.class, PrimaryRoleQueryService.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, AuthCommandService.class, AuthController.class,
        PermissionController.class, DocumentQueryController.class, DocumentFileService.class,
        SearchController.class, SearchFacade.class, AccessibleDocumentQueryService.class,
        SearchQueryCommandService.class, SearchConversationCommandService.class,
        SearchConversationQueryService.class, SearchResultCommandService.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean FixtureStorage fixtureStorage() { return new FixtureStorage(); }
        @Bean HybridSearchProperties hybridProperties() {
            var value = new HybridSearchProperties(); value.setEnabled(false); return value;
        }
    }

    /** 합성 파일 바이트만 메모리에 보관하고 원본 읽기 횟수를 계측한다. 외부 저장소에는 접속하지 않는다. */
    static class FixtureStorage implements FileStorageService {
        final Map<StoredFile, byte[]> bytes = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        @Override public byte[] read(StoredFile file) {
            reads.incrementAndGet();return Objects.requireNonNull(bytes.get(file)).clone();
        }
        @Override public StoredFile store(InputStream input, long size, String type, String key) {
            throw new UnsupportedOperationException("Read-only synthetic fixture");
        }
        @Override public void delete(StoredFile file) { throw new UnsupportedOperationException("Read-only fixture"); }
    }

    @Test
    @DisplayName("실제 HTTP 회수 확정 뒤 자동 승격해도 새 응답에 금지 문구가 없고 대조 문서는 정상이다")
    void realHttpRevocationSurvivesPromotion() throws Exception {
        directory = Path.of(System.getenv("PERMISSION_HA_RUN_DIR"));
        Files.createDirectories(directory);
        event("start", Map.of("purpose", "인증 HTTP 회수·승격·응답 차단", "location", "루프백 HTTP → IAP → 격리 OpenSQL·OpenProxy·역할 어댑터", "criteria", "금지 내용 0·대조 정상·원장 보존", "post_recovery_repetitions", 3));
        String password = UUID.randomUUID().toString();
        Fixture fixture = new TransactionTemplate(manager).execute(status -> seed(password));
        event("search-strategy",Map.of("hybrid_enabled",searchProperties.isEnabled(),"controlled_vector_candidates",2));
        require(!searchProperties.isEnabled(),"fixture_hybrid_strategy_mismatch");
        configureControlledSearch(fixture);
        // 1. 실제 로그인 응답의 토큰은 메모리에서만 보관하고 원장에는 상태·해시도 남기지 않는다.
        String ownerToken = login(fixture.ownerEmail(), password, "owner");
        String targetToken = login(fixture.targetEmail(), password, "target");
        var anonymous = request("anonymous", "detail", "/api/documents/" + fixture.revoked().document(), null, null);
        require(anonymous.statusCode()==401, "anonymous_must_be_401");
        checkDocuments("before", fixture, targetToken, false);
        checkSearch("before", targetToken, true, fixture);
        // 2. 정상 204 뒤 의도적으로 stale 캐시를 복원해 최종 권한 원장 검증을 자극한다.
        var revoked = request("revoke", "permission", "/permissions/documents/"+fixture.revoked().document()+"/"+fixture.permission(), ownerToken, "DELETE");
        require(revoked.statusCode()==204,"revocation_not_confirmed");
        new TransactionTemplate(manager).execute(status -> {
            new JdbcTemplate(dataSource).update("UPDATE user_document_access_cache SET invalidated_at=NULL WHERE source_type='DIRECT_DOCUMENT_PERMISSION' AND source_id=?",fixture.permission());return null;
        });
        checkDocuments("after-confirmation", fixture, targetToken, true);
        checkSearch("after-confirmation", targetToken, false, fixture);
        event("revocation-confirmed",Map.of("request_key",requestKey.toString(),"stale_cache_injected",true));
        Files.writeString(directory.resolve("ready"),"confirmed_revocation_ready_for_primary_loss\n",StandardOpenOption.CREATE_NEW);
        // 3. 외부 제어기가 실제 자동 승격을 관측할 때까지 다음 성공을 추정하지 않는다.
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        while(!Files.exists(directory.resolve("fault.done"))&&System.nanoTime()<deadline)Thread.sleep(100);
        require(Files.exists(directory.resolve("fault.done")),"promotion_observation_missing");
        for(int repeat=1;repeat<=3;repeat++) {
            checkDocuments("after-promotion-"+repeat,fixture,targetToken,true);
            checkSearch("after-promotion-"+repeat,targetToken,false,fixture);
        }
        // 4. 새 Primary의 실제 K 원장과 권한 삭제를 독립 SQL로 대조한다.
        new TransactionTemplate(manager).execute(status -> {
            var jdbc=new JdbcTemplate(dataSource);
            require(Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_is_in_recovery()",Boolean.class)),"final_connection_not_primary");
            require(jdbc.queryForObject("SELECT count(*) FROM permission_revocation_requests WHERE request_id=?",Long.class,requestKey)==1,"receipt_not_preserved");
            require(jdbc.queryForObject("SELECT count(*) FROM document_permissions WHERE id=?",Long.class,fixture.permission())==0,"permission_resurrected");
            return null;
        });
        event("summary",Map.of("request_key",requestKey.toString(),"receipt_rows",1,"remaining_permissions",0,"forbidden_content",0,"post_recovery_repetitions",3,"storage_reads",storage.reads.get(),"passed",true));
    }

    private Fixture seed(String password) {
        String token=UUID.randomUUID().toString();
        User owner=users.save(User.builder().email("owner-"+token+"@example.invalid").passwordHash(encoder.encode(password)).name("합성 소유자").status(UserStatus.ACTIVE).build());
        User target=users.save(User.builder().email("target-"+token+"@example.invalid").passwordHash(encoder.encode(password)).name("합성 대상자").status(UserStatus.ACTIVE).build());
        com.opensource.docgrid.domain.user.entity.Role role=roles.findByCode("USER").orElseGet(()->roles.save(com.opensource.docgrid.domain.user.entity.Role.builder().code("USER").name("합성 사용자 역할").build()));
        for(User user:List.of(owner,target))userRoles.save(UserRole.builder().user(user).role(role).assignedBy(owner).assignedAt(LocalDateTime.now()).build());
        EmbeddingModel model=models.save(EmbeddingModel.builder().provider(com.opensource.docgrid.domain.embedding.enums.EmbeddingProvider.MOCK).modelName("permission-http-"+token).modelVersion("1").dimension(1024).distanceMetric(com.opensource.docgrid.domain.embedding.enums.DistanceMetric.COSINE).isActive(false).isSearchable(false).vectorStorageStrategy(com.opensource.docgrid.domain.embedding.enums.VectorStorageStrategy.SINGLE_DIMENSION).build());
        var revoked=seedDocument(owner,REVOKED,model);var allowed=seedDocument(owner,ALLOWED,model);
        var request=new GrantPermissionRequest(PermissionTargetType.USER,target.getId(),null,null,PermissionType.READ,null);
        Long permission=permissions.grantPermission(revoked.document(),owner.getId(),request).permissionId();
        permissions.grantPermission(allowed.document(),owner.getId(),request);
        return new Fixture(owner.getEmail(),target.getEmail(),revoked,allowed,permission,model);
    }

    private DocumentFixture seedDocument(User owner,String sentinel,EmbeddingModel model) {
        byte[] bytes=(sentinel+"\n"+UUID.randomUUID()+"\n").getBytes(StandardCharsets.UTF_8);String key=UUID.randomUUID()+".txt";
        var file=files.save(FileObject.builder().bucketName("synthetic").objectKey(key).originalFilename("synthetic.txt").contentType("text/plain").fileSize((long)bytes.length).fileHash(hash(bytes)).storageProvider(StorageProvider.LOCAL).uploadedBy(owner).uploadedAt(LocalDateTime.now()).build());
        storage.bytes.put(new StoredFile(StorageProvider.LOCAL,"synthetic",key),bytes);
        var document=documents.save(Document.builder().owner(owner).title(sentinel).documentType(DocumentType.TXT).sourceType(DocumentSourceType.UPLOAD).status(DocumentStatus.INDEXED).visibility(VisibilityType.PRIVATE).build());
        var version=versions.save(DocumentVersion.builder().document(document).fileObject(file).versionNo(1).titleSnapshot(sentinel).originalFilename("synthetic.txt").contentType("text/plain").status(DocumentVersionStatus.INDEXED).createdBy(owner).build());
        document.updateCurrentVersion(version);
        var chunk=chunks.save(DocumentChunk.builder().documentVersion(version).chunkIndex(0).chunkText(sentinel).tokenCount(1).charStart(0).charEnd(sentinel.length()).build());
        float[] vector=new float[1024];vector[0]=1;
        var embedding=embeddings.save(Embedding.builder().chunk(chunk).document(document).documentVersion(version).embeddingModel(model).vector(vector).dimension(1024).status(EmbeddingStatus.ACTIVE).build());
        return new DocumentFixture(document.getId(),chunk.getId(),embedding.getId(),sentinel);
    }

    private void configureControlledSearch(Fixture f) {
        float[] vector=new float[1024];vector[0]=1;
        when(queryEmbedding.embed(anyString())).thenReturn(new EmbedResult(f.model(),vector));
        // 후보 대역은 stale 문서도 반환한다. 실제 SearchFacade의 최종 Primary 인가가 제거해야 한다.
        when(candidates.search(any(),any(),anyList(),anyInt())).thenReturn(List.of(candidate(f.revoked()),candidate(f.allowed())));
        when(rag.enqueue(anyLong(),anyLong(),anyString(),anyList())).thenAnswer(invocation -> {
            List<VectorSearchCandidate> verified=invocation.getArgument(3);
            String answer=verified.stream().map(VectorSearchCandidate::chunkText).reduce("",(a,b)->a+" "+b);
            List<CitationResponse> citations=new ArrayList<>();for(int i=0;i<verified.size();i++)citations.add(CitationResponse.of(i+1,verified.get(i)));
            return RagEnqueueOutcome.done(new RagAnswer(answer,citations));
        });
    }

    private VectorSearchCandidate candidate(DocumentFixture f) {
        return new VectorSearchCandidate(f.embedding(),f.chunk(),f.document(),f.sentinel(),1,f.sentinel(),BigDecimal.ONE);
    }

    private String login(String email,String password,String actor) throws Exception {
        long start=System.nanoTime();
        var response=client.send(HttpRequest.newBuilder(uri("/auth/login")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Accept","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email",email,"password",password)))).build(),HttpResponse.BodyHandlers.ofByteArray());
        event("login",Map.of("actor",actor,"status",response.statusCode(),"latency_ms",(System.nanoTime()-start)/1_000_000.0));
        require(response.statusCode()==200,"login_failed");
        String token=json.readTree(response.body()).path("data").path("accessToken").asText();
        require(!token.isBlank(),"login_token_missing");return token;
    }

    private void checkDocuments(String phase,Fixture f,String token,boolean blocked) throws Exception {
        for(String suffix:List.of("","/content","/file")) {
            int before=storage.reads.get();
            var response=request(phase,"revoked"+suffix,"/api/documents/"+f.revoked().document()+suffix,token,null);
            require(response.statusCode()==(blocked?403:200),"target_status_mismatch");
            require(new String(response.body(),StandardCharsets.UTF_8).contains(REVOKED)!=blocked,"target_content_mismatch");
            if(blocked&&suffix.equals("/file"))require(storage.reads.get()==before,"blocked_file_storage_called");
            var control=request(phase,"allowed"+suffix,"/api/documents/"+f.allowed().document()+suffix,token,null);
            require(control.statusCode()==200&&new String(control.body(),StandardCharsets.UTF_8).contains(ALLOWED),"allowed_control_unavailable");
            if(suffix.equals("/file"))require(hash(control.body()).equals(hash(storage.bytes.values().stream().filter(b->new String(b,StandardCharsets.UTF_8).contains(ALLOWED)).findFirst().orElseThrow())),"allowed_file_hash_mismatch");
        }
    }

    private void checkSearch(String phase,String token,boolean includeRevoked,Fixture f) throws Exception {
        var response=request(phase,"new-search","/search",token,"POST");
        require(response.statusCode()==200,"search_unavailable");
        String body=new String(response.body(),StandardCharsets.UTF_8);
        require(body.contains(ALLOWED)&&body.contains(REVOKED)==includeRevoked,"search_content_mismatch");
        var data=json.readTree(body).path("data");
        require(data.path("results").size()==(includeRevoked?2:1),"search_result_count_mismatch");
        require(data.path("citations").size()==(includeRevoked?2:1),"search_citation_count_mismatch");
        if(!includeRevoked)for(var result:data.path("results"))require(result.path("documentId").asLong()!=f.revoked().document(),"revoked_document_returned");
    }

    private HttpResponse<byte[]> request(String phase,String target,String path,String token,String method) throws Exception {
        var builder=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if("DELETE".equals(method))builder.header("Idempotency-Key",requestKey.toString()).DELETE();
        else if("POST".equals(method))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"queryText\":\"합성 권한 질문\",\"topK\":5}"));
        else builder.GET();
        long start=System.nanoTime();var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        boolean forbidden=new String(response.body(),StandardCharsets.UTF_8).contains(REVOKED);
        event("http",Map.of("phase",phase,"target",target,"status",response.statusCode(),"latency_ms",(System.nanoTime()-start)/1_000_000.0,"revoked_sentinel_present",forbidden,"response_sha256",hash(response.body())));
        return response;
    }
    private URI uri(String path) {return URI.create("http://127.0.0.1:"+port+path);}
    private void event(String kind,Map<String,?> detail) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("at_kst",OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());row.put("event",kind);row.putAll(detail);
        String line=json.writeValueAsString(row);Files.writeString(directory.resolve("http.jsonl"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("EVIDENCE "+line);
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static void require(boolean valid,String category) {if(!valid)throw new AssertionError(category);}
    /** 합성 문서 ID와 검출 문구만 보관한다. */
    private record DocumentFixture(Long document,Long chunk,Long embedding,String sentinel) {}
    /** 합성 로그인 ID를 메모리에서만 다루고 원시 HTTP 로그에 출력하지 않는다. */
    private record Fixture(String ownerEmail,String targetEmail,DocumentFixture revoked,DocumentFixture allowed,Long permission,EmbeddingModel model) {}
}
