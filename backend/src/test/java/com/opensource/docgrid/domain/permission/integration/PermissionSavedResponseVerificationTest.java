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

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.opensource.docgrid.domain.rag.service.OllamaClient;
import com.opensource.docgrid.domain.rag.service.PromptBuilder;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.rag.service.command.RagResponseClaimService;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Verifies current authorization on saved HTTP responses and actual model HTTP input.
 * Runs only against newly created local containers; model text and vector candidates are controlled.
 * It records each security verdict before asserting, and never changes production implementation.
 */
@Tag("permission-current-auth")
@org.springframework.test.context.ActiveProfiles("permission-verification")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = PermissionSavedResponseVerificationTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "search.hybrid.enabled=false", "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=OFF",
    "spring.datasource.url=${PERMISSION_VERIFY_JDBC_URL}", "spring.datasource.username=${PERMISSION_VERIFY_USER:verify_app}",
    "spring.datasource.password=${PERMISSION_VERIFY_PASSWORD}", "spring.datasource.hikari.maximum-pool-size=5",
    "spring.datasource.hikari.connection-timeout=5000", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.locations=classpath:db/migration", "spring.jpa.open-in-view=false",
    "permission.revocation.synchronous-enabled=false", "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=${PERMISSION_VERIFY_REDIS_PORT}", "spring.data.redis.password=",
    "spring.data.redis.database=${PERMISSION_VERIFY_REDIS_DB:0}",
    "jwt.secret=${PERMISSION_VERIFY_JWT_SECRET}", "jwt.expiration=600", "management.server.port=0"
})
@Timeout(180)
class PermissionSavedResponseVerificationTest {
    private static final String REVOKED = "REVOKED_AUTH_SENTINEL_20261019";
    private static final String ALLOWED = "ALLOWED_AUTH_SENTINEL_20261019";
    private static final ModelHarness MODEL = new ModelHarness();
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
    @Autowired RagFacade rag;
    @Autowired RagResponseRepository responses;
    @Autowired RagResponseClaimService claims;
    @MockitoBean QueryEmbeddingService queryEmbedding;
    @MockitoBean VectorSearchQueryService candidates;
    @MockitoBean HybridSearchQueryService hybrid;
    @MockitoBean McpAccessTokenCommandService mcp;
    @MockitoBean AuthQueryService me;
    @MockitoBean PermissionTargetUserQueryService targetUsers;
    private Path directory;
    private String runId;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final UUID requestKey = UUID.randomUUID();

    @DynamicPropertySource
    static void localModel(DynamicPropertyRegistry properties) {
        properties.add("ollama.server.base-url", MODEL::url);
    }

    @AfterAll
    static void closeModel() { MODEL.close(); }

    /** Imports actual controllers, authorization, persistence, and RAG; no scheduled workers run. */
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
        SearchConversationQueryService.class, SearchAnswerQueryService.class,
        SearchResultCommandService.class, GlobalExceptionHandler.class, RagFacade.class,
        PromptBuilder.class, OllamaClient.class, OllamaServerConfig.class,
        RagResponseCommandService.class, ResponseCitationCommandService.class, RagResponseClaimService.class,
        com.opensource.docgrid.domain.rag.service.RagDocumentAccessService.class,
        com.opensource.docgrid.domain.rag.service.RagPromptPreparationService.class})
    static class Config {
        @Bean FixtureStorage fixtureStorage() { return new FixtureStorage(); }
        @Bean HybridSearchProperties hybridProperties() {
            var value = new HybridSearchProperties(); value.setEnabled(false); return value;
        }
        @Bean TransactionTemplate ragTransactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
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

    @Test @Order(1)
    @DisplayName("Saved answer and conversation must exclude revoked content")
    void savedHttpResponses() throws Exception {
        var f = prepare("AUTH-SAVED-20261019-R1");
        String password = currentPassword;
        String owner = login(f.ownerEmail(), password, "owner");
        String target = login(f.targetEmail(), password, "target");
        var query = search("before", target, null);
        long queryId = query.path("queryId").asLong();
        long conversationId = query.path("conversationId").asLong();
        require(query.path("results").size() == 2, "initial_results_must_have_two_documents");
        require(request("anonymous", "saved-answer", "/search/"+queryId, null, null).statusCode()==401, "anonymous_not_blocked");
        require(request("other-owner", "saved-answer", "/search/"+queryId, owner, null).statusCode()==404, "query_owner_check_missing");
        var pending = request("before", "pending-answer", "/search/"+queryId, target, null);
        require(pending.statusCode()==200 && contains(pending, REVOKED), "pending_fixture_not_visible");
        complete(queryId);
        var before = request("before", "saved-answer", "/search/"+queryId, target, null);
        require(before.statusCode()==200 && contains(before, REVOKED), "completed_fixture_not_visible");
        revoke(f, owner, target);
        var fresh = search("after-new-search", target, null);
        require(fresh.path("results").size()==1 && !fresh.path("results").toString().contains(REVOKED), "new_search_not_filtered");
        complete(fresh.path("queryId").asLong());
        int answerExposures=0, conversationExposures=0;
        for (int repeat=1;repeat<=3;repeat++) {
            var answer=request("after-revocation-"+repeat,"saved-answer","/search/"+queryId,target,null);
            var conversation=request("after-revocation-"+repeat,"saved-conversation","/search/conversations/"+conversationId,target,null);
            require(answer.statusCode()==200 && contains(answer,ALLOWED),"allowed_saved_answer_unavailable");
            require(conversation.statusCode()==200 && contains(conversation,ALLOWED),"allowed_conversation_unavailable");
            var data=json.readTree(answer.body()).path("data");
            event("response-fields",Map.of("repeat",repeat,"revoked_result",data.path("results").toString().contains(REVOKED),
                "revoked_answer",data.path("answer").toString().contains(REVOKED),
                "revoked_citation",data.path("citations").toString().contains(REVOKED)));
            if(contains(answer,REVOKED))answerExposures++;
            if(contains(conversation,REVOKED))conversationExposures++;
        }
        event("verdict",Map.of("expected_forbidden_responses",0,"saved_answer_exposures",answerExposures,
            "conversation_exposures",conversationExposures,"repetitions_each",3,
            "security_pass",answerExposures+conversationExposures==0));
        require(answerExposures+conversationExposures==0,"authorization_violation_saved_http_responses");
    }

    @Test @Order(2)
    @DisplayName("First model request after revocation must exclude an earlier queued source")
    void queuedPromptAfterRevocation() throws Exception {
        var f=prepare("AUTH-QUEUED-20261019-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");
        String target=login(f.targetEmail(),currentPassword,"target");
        var query=search("before",target,null);
        require(query.path("results").size()==2,"queued_fixture_not_ready");
        revoke(f,owner,target);
        require(MODEL.prompts.isEmpty(),"model_called_before_revocation");
        complete(query.path("queryId").asLong());
        boolean exposed=MODEL.prompts.get(0).contains(REVOKED);
        event("verdict",Map.of("model_requests",MODEL.prompts.size(),"revoked_model_input",exposed,
            "model_started_after_revocation",true,"security_pass",!exposed));
        require(!exposed,"authorization_violation_queued_model_input");
    }

    @Test @Order(3)
    @DisplayName("A new allowed search must not reuse a revoked answer in model history")
    void pastAnswerInNewModelInput() throws Exception {
        var f=prepare("AUTH-HISTORY-20261019-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");
        String target=login(f.targetEmail(),currentPassword,"target");
        var initial=search("before",target,null);
        complete(initial.path("queryId").asLong());
        require(MODEL.prompts.size()==1,"initial_model_call_missing");
        revoke(f,owner,target);
        var follow=search("after-followup",target,initial.path("conversationId").asLong());
        boolean currentContainsRevoked=follow.path("results").toString().contains(REVOKED);
        require(follow.path("results").size()==1 && !currentContainsRevoked,"followup_search_not_filtered");
        complete(follow.path("queryId").asLong());
        require(MODEL.prompts.size()==2,"followup_model_call_missing");
        String prompt=MODEL.prompts.get(1);
        String history=prompt.contains("[1]") ? prompt.substring(0,prompt.indexOf("[1]")) : prompt;
        boolean exposed=history.contains(REVOKED);
        event("verdict",Map.of("current_results_revoked",currentContainsRevoked,"model_requests",2,
            "revoked_history_input",exposed,"allowed_source_present",prompt.contains(ALLOWED),"security_pass",!exposed));
        require(!exposed,"authorization_violation_history_model_input");
    }

    @org.junit.jupiter.params.ParameterizedTest @Order(4)
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @DisplayName("Revocation during model success or fallback must not persist a forbidden answer")
    void inFlightRevocation(boolean providerFailure) throws Exception {
        var f=prepare("AUTH-INFLIGHT-20261019-"+(providerFailure?"FALLBACK":"SUCCESS")+"-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");
        String target=login(f.targetEmail(),currentPassword,"target");
        var query=search("before",target,null);
        MODEL.hold=true;MODEL.fail=providerFailure;
        MODEL.received=new java.util.concurrent.CountDownLatch(1);MODEL.released=new java.util.concurrent.CountDownLatch(1);
        var execution=java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {complete(query.path("queryId").asLong(),com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED);}
            catch(Exception exception){throw new java.util.concurrent.CompletionException(exception);}
        });
        try {
            require(MODEL.received.await(5,java.util.concurrent.TimeUnit.SECONDS),"held_model_not_received");
            int idleTransactions=new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction' AND pid<>pg_backend_pid()",Integer.class);
            event("model-wait-db",Map.of("idle_in_transaction",idleTransactions));
            require(idleTransactions==0,"model_wait_holds_database_transaction");
            revoke(f,owner,target);
        } finally { MODEL.released.countDown(); }
        execution.get(10,java.util.concurrent.TimeUnit.SECONDS);
        var response=request("after-model","saved-answer","/search/"+query.path("queryId").asLong(),target,null);
        var saved=responses.findByQuery_Id(query.path("queryId").asLong()).orElseThrow();
        boolean exposed=contains(response,REVOKED) || (saved.getAnswerText()!=null && saved.getAnswerText().contains(REVOKED));
        event("verdict",Map.of("provider_failure",providerFailure,"revoked_response_or_persisted_answer",exposed,"security_pass",!exposed));
        require(!exposed,"authorization_violation_inflight_result");
    }

    @Test @Order(5)
    @DisplayName("Legacy or malformed provenance must withhold answers and history")
    void legacyAndMalformedSources() throws Exception {
        var f=prepare("AUTH-LEGACY-20261019-R1");
        String target=login(f.targetEmail(),currentPassword,"target");
        var query=search("before",target,null);long queryId=query.path("queryId").asLong();complete(queryId);
        for(String metadata:java.util.Arrays.asList(null,"{broken","[-1]","[\"1\"]")) {
            new JdbcTemplate(dataSource).update("UPDATE rag_responses SET source_document_ids_json=? WHERE query_id=?",metadata,queryId);
            var response=request("legacy-"+(metadata==null?"null":Integer.toHexString(metadata.hashCode())),"answer","/search/"+queryId,target,null);
            var data=json.readTree(response.body()).path("data");
            require(data.path("citations").isEmpty() && !data.path("answer").asText().contains(REVOKED),"legacy_answer_not_withheld");
        }
        when(candidates.search(any(),any(),anyList(),anyInt())).thenReturn(List.of(candidate(f.allowed())));
        var follow=search("legacy-followup",target,query.path("conversationId").asLong());complete(follow.path("queryId").asLong());
        boolean exposed=MODEL.prompts.get(1).contains(REVOKED);
        event("verdict",Map.of("metadata_cases",4,"revoked_history_input",exposed,"security_pass",!exposed));
        require(!exposed,"legacy_history_not_withheld");
    }

    @Test @Order(6)
    @DisplayName("Revoked transitive history sources must suppress a later answer citing only allowed documents")
    void transitiveSources() throws Exception {
        var f=prepare("AUTH-TRANSITIVE-20261019-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");String target=login(f.targetEmail(),currentPassword,"target");
        var first=search("first",target,null);complete(first.path("queryId").asLong());
        when(candidates.search(any(),any(),anyList(),anyInt())).thenReturn(List.of(candidate(f.allowed())));
        var second=search("second",target,first.path("conversationId").asLong());complete(second.path("queryId").asLong());
        require(responses.findByQuery_Id(second.path("queryId").asLong()).orElseThrow().getSourceDocumentIdsJson().contains(f.revoked().document().toString()),"transitive_source_not_recorded");
        revoke(f,owner,target);
        var saved=request("after-transitive-revocation","saved-answer","/search/"+second.path("queryId").asLong(),target,null);
        var third=search("third",target,first.path("conversationId").asLong());complete(third.path("queryId").asLong());
        boolean exposed=contains(saved,REVOKED)||MODEL.prompts.get(2).contains(REVOKED);
        event("verdict",Map.of("revoked_transitive_output_or_input",exposed,"security_pass",!exposed));
        require(!exposed,"transitive_authorization_violation");
    }

    @Test @Order(7)
    @DisplayName("Timeout fallback must use current permitted sources instead of the old top result")
    void timeoutFallbackSources() throws Exception {
        var f=prepare("AUTH-TIMEOUT-20261019-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");String target=login(f.targetEmail(),currentPassword,"target");
        var query=search("before",target,null);long queryId=query.path("queryId").asLong();
        revoke(f,owner,target);
        require(rag.failIfStillProcessing(responses.findByQuery_Id(queryId).orElseThrow().getId(),queryId),"timeout_not_completed");
        var response=request("after-timeout","saved-answer","/search/"+queryId,target,null);
        var data=json.readTree(response.body()).path("data");
        boolean exposed=contains(response,REVOKED);
        event("verdict",Map.of("revoked_fallback",exposed,"allowed_fallback",data.path("answer").asText().contains(ALLOWED),"security_pass",!exposed));
        require(!exposed && data.path("answer").asText().contains(ALLOWED),"timeout_source_mismatch");
    }

    @Test @Order(8)
    @DisplayName("A queued job with no readable source must finish without a model request")
    void allSourcesRevoked() throws Exception {
        var f=prepare("AUTH-EMPTY-20261019-R1");
        String owner=login(f.ownerEmail(),currentPassword,"owner");String target=login(f.targetEmail(),currentPassword,"target");
        var query=search("before",target,null);revoke(f,owner,target);
        Long remaining=new JdbcTemplate(dataSource).queryForObject(
            "SELECT id FROM document_permissions WHERE document_id=?",Long.class,f.allowed().document());
        require(request("revoke-control","permission","/permissions/documents/"+f.allowed().document()+"/"+remaining,owner,"DELETE").statusCode()==204,"control_revocation_failed");
        complete(query.path("queryId").asLong(),com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED);
        var response=request("after-empty","saved-answer","/search/"+query.path("queryId").asLong(),target,null);
        boolean exposed=contains(response,REVOKED)||contains(response,ALLOWED);
        event("verdict",Map.of("model_requests",MODEL.prompts.size(),"revoked_content",exposed,"security_pass",!exposed&&MODEL.prompts.isEmpty()));
        require(!exposed&&MODEL.prompts.isEmpty(),"empty_sources_not_closed");
    }

    private String currentPassword;

    private Fixture prepare(String id) throws Exception {
        id=id.replace("-R1", "-"+System.getenv("PERMISSION_VERIFY_RUN_SUFFIX"));
        runId=id;
        directory=Path.of(System.getenv("PERMISSION_VERIFY_RUN_DIR")).resolve(id);
        Files.createDirectories(directory);
        MODEL.directory=directory;MODEL.prompts.clear();
        MODEL.hold=false;MODEL.fail=false;
        storage.bytes.clear();
        currentPassword=UUID.randomUUID().toString();
        event("start",Map.of("source_revision",System.getenv("PERMISSION_VERIFY_SOURCE_REVISION"),
            "real_http_auth",true,"fresh_local_database",true,"controlled_model",true));
        Fixture f=new TransactionTemplate(manager).execute(status -> seed(currentPassword));
        float[] vector=new float[1024];vector[0]=1;
        when(queryEmbedding.embed(anyString())).thenReturn(new EmbedResult(f.model(),vector));
        when(candidates.search(any(),any(),anyList(),anyInt())).thenReturn(List.of(candidate(f.revoked()),candidate(f.allowed())));
        require(!searchProperties.isEnabled(),"unexpected_search_strategy");
        return f;
    }

    private void revoke(Fixture f,String owner,String target) throws Exception {
        var revoked=request("revoke","permission","/permissions/documents/"+f.revoked().document()+"/"+f.permission(),owner,"DELETE");
        require(revoked.statusCode()==204,"revocation_not_committed");
        require(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM document_permissions WHERE id=?",Long.class,f.permission())==0,"permission_row_remains");
        checkDocuments("after-revocation",f,target,true);
        event("revocation-confirmed",Map.of("remaining_permission_rows",0,"target_direct_access",403,"allowed_direct_access",200));
    }

    private void complete(long queryId) throws Exception {
        complete(queryId, com.opensource.docgrid.domain.search.enums.ResultStatus.SUCCESS);
    }

    private void complete(long queryId, com.opensource.docgrid.domain.search.enums.ResultStatus expected) throws Exception {
        var job=responses.findByQuery_Id(queryId).orElseThrow();
        var claim=claims.claimNext().orElseThrow();
        require(claim.jobId().equals(job.getId()),"unexpected_queued_job");
        boolean completed=rag.processJob(claim.jobId(),claim.token());
        var saved=responses.findByQuery_Id(queryId).orElseThrow();
        event("model-completion",Map.of("query_id",queryId,"completed",completed,"status",saved.getStatus().name()));
        require(completed && saved.getStatus()==expected,"model_completion_incomplete");
    }

    private com.fasterxml.jackson.databind.JsonNode search(String phase,String token,Long conversation) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("queryText","Explain the synthetic authorized document.");body.put("topK",5);
        if(conversation!=null)body.put("conversationId",conversation);
        var response=client.send(HttpRequest.newBuilder(uri("/search")).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
        recordHttp(phase,"new-search",response);
        require(response.statusCode()==200,"search_request_failed");
        return json.readTree(response.body()).path("data");
    }

    private static boolean contains(HttpResponse<byte[]> response,String text) {
        return new String(response.body(),StandardCharsets.UTF_8).contains(text);
    }

    /** Captures only synthetic model HTTP input and produces deterministic streaming test text. */
    static class ModelHarness {
        final HttpServer server;
        final List<String> prompts=Collections.synchronizedList(new ArrayList<>());
        volatile Path directory;
        volatile boolean hold, fail;
        volatile java.util.concurrent.CountDownLatch received, released;
        ModelHarness() {
            try {
                server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                server.setExecutor(Executors.newCachedThreadPool(r -> {Thread t=new Thread(r,"controlled-model");t.setDaemon(true);return t;}));
                server.createContext("/api/generate",exchange -> {
                    ObjectMapper mapper=new ObjectMapper();
                    var body=mapper.readTree(exchange.getRequestBody().readAllBytes());
                    String prompt=body.path("prompt").asText();prompts.add(prompt);
                    Files.writeString(directory.resolve("model-events.jsonl"),mapper.writeValueAsString(Map.of("at_kst",OffsetDateTime.now(ZoneOffset.ofHours(9)).toString(),"event","model-request-received","request_number",prompts.size(),"revoked_sentinel_present",prompt.contains(REVOKED)))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    Files.writeString(directory.resolve("model-input-"+prompts.size()+".json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body)+"\n");
                    if(hold) {
                        hold=false;received.countDown();
                        try {if(!released.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("test_release_missing");}
                        catch(InterruptedException exception){Thread.currentThread().interrupt();throw new java.io.IOException(exception);}
                    }
                    if(fail) {exchange.sendResponseHeaders(503,-1);exchange.close();return;}
                    String answer="Synthetic controlled answer. "+(prompt.contains(REVOKED)?REVOKED+" ":"")+ALLOWED;
                    byte[] output=(mapper.writeValueAsString(Map.of("model","controlled-permission-model","response",answer,"done",true,"prompt_eval_count",10,"eval_count",5))+"\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");
                    exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
                });server.start();
            } catch(Exception exception) {throw new IllegalStateException("controlled_model_start_failed",exception);}
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void close(){server.stop(0);}
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

    private HttpResponse<byte[]> request(String phase,String target,String path,String token,String method) throws Exception {
        var builder=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if("DELETE".equals(method))builder.header("Idempotency-Key",requestKey.toString()).DELETE();
        else if("POST".equals(method))builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"queryText\":\"합성 권한 질문\",\"topK\":5}"));
        else builder.GET();
        long start=System.nanoTime();var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        recordHttp(phase,target,response);
        return response;
    }
    private void recordHttp(String phase,String target,HttpResponse<byte[]> response) throws Exception {
        String body=new String(response.body(),StandardCharsets.UTF_8);
        String safe=body.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<EMAIL_OMITTED>")
            .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "<TOKEN_OMITTED>");
        String filename=phase+"-"+target.replace('/','_')+".body";
        Files.writeString(directory.resolve(filename),safe);
        event("http",Map.of("phase",phase,"target",target,"status",response.statusCode(),
            "revoked_sentinel_present",body.contains(REVOKED),"allowed_sentinel_present",body.contains(ALLOWED),"body_artifact",filename));
    }
    private URI uri(String path) {return URI.create("http://127.0.0.1:"+port+path);}
    private synchronized void event(String kind,Map<String,?> detail) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("at_kst",OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());row.put("event",kind);row.put("run_id",runId);row.putAll(detail);
        String line=json.writeValueAsString(row);Files.writeString(directory.resolve("events.jsonl"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("EVIDENCE "+line);
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
