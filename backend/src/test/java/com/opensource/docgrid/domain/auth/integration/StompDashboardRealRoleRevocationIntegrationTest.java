package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

/**
 * 실제 HTTP 역할 회수, PostgreSQL 커밋, Redis 캐시 무효화와 STOMP 전송 인가를 연결한다.
 *
 * <p>테스트 전용 사용자만 생성·삭제하며, 자동 재검증을 늦춘 채 회수 직후의
 * 새 구독과 기존 구독 push가 차단되는지 확인한다. GCP 복제본 라우팅은 범위 밖이다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP 실제 역할 회수 통합 테스트")
class StompDashboardRealRoleRevocationIntegrationTest {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final long TIMEOUT_SECONDS = 5;
    private static final long NO_DELIVERY_MILLIS = 300;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private DashboardWebSocketController dashboardWebSocketController;

    private WebSocketStompClient stompClient;
    private User adminCaller;
    private User targetUser;
    private StompSession adminSession;
    private StompSession oldSession;
    private StompSession oldIdleSession;
    private StompSession newSession;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-stomp-real-role-revocation-integration-test-secret-2026");
        registry.add("auth.stomp.session-revalidation.interval", () -> "1h");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        // 1. 세션을 먼저 닫고 이 테스트가 만든 사용자·역할과 Redis 키만 제거한다.
        disconnect(newSession);
        disconnect(oldIdleSession);
        disconnect(oldSession);
        disconnect(adminSession);
        if (targetUser != null) {
            redisTemplate.delete(roleCacheKey(targetUser.getId()));
            redisTemplate.delete(roleEpochKey(targetUser.getId()));
            deleteTestUser(targetUser.getId());
        }
        if (adminCaller != null) {
            redisTemplate.delete(roleCacheKey(adminCaller.getId()));
            redisTemplate.delete(roleEpochKey(adminCaller.getId()));
            deleteTestUser(adminCaller.getId());
        }
        stompClient.stop();
    }

    @Test
    @DisplayName("HTTP 역할 회수 뒤 주기 재검증 없이 새 구독과 기존 구독 push가 차단된다")
    void revokesRealAdminRole_andBlocksDashboardSessionsBeforeScheduledRevalidation() throws Exception {
        // 1. seed 역할은 재사용하되 사용자와 매핑은 이 테스트만 소유한다.
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminCaller = createTestUser("caller");
        targetUser = createTestUser("target");
        grantAdmin(adminCaller, adminRole);
        grantAdmin(targetUser, adminRole);
        String targetEmail = targetUser.getEmail();
        String targetToken = jwtProvider.generateToken(targetUser.getId(), targetEmail);
        String cacheKey = roleCacheKey(targetUser.getId());
        String epochKey = roleEpochKey(targetUser.getId());
        redisTemplate.delete(cacheKey);
        redisTemplate.delete(epochKey);

        // 2. 실제 STOMP CONNECT와 SUBSCRIBE가 DB 역할을 Redis에 캐시하고 브로커에 등록한다.
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        BlockingQueue<DashboardSummaryResponse> activeReceived = new LinkedBlockingQueue<>();
        oldSession = connect(targetToken, new LinkedBlockingQueue<>());
        oldSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(received));
        adminSession = connect(
            jwtProvider.generateToken(adminCaller.getId(), adminCaller.getEmail()),
            new LinkedBlockingQueue<>()
        );
        adminSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(activeReceived));
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> hasDashboardSubscription(targetEmail) && hasDashboardSubscription(adminCaller.getEmail()));
        dashboardWebSocketController.sendDashboardUpdate(sampleSummary(1L));
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(activeReceived.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        BlockingQueue<Throwable> oldIdleFailures = new LinkedBlockingQueue<>();
        oldIdleSession = connect(targetToken, oldIdleFailures);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).contains("ADMIN");

        // 3. 실제 관리자 HTTP 요청이 DB 트랜잭션을 커밋한 뒤 Redis 캐시를 무효화한다.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtProvider.generateToken(adminCaller.getId(), adminCaller.getEmail()));
        ResponseEntity<String> response = restTemplate.exchange(
            "/admin/users/" + targetUser.getId() + "/roles/ADMIN",
            HttpMethod.DELETE,
            new HttpEntity<>(null, headers),
            String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRoleRepository.existsByUserIdAndRoleCode(targetUser.getId(), "ADMIN")).isFalse();
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
        assertThat(redisTemplate.opsForValue().get(epochKey)).isEqualTo("1");

        // 실제 무효화 결과를 확인한 뒤 의도적으로 옛 ADMIN 캐시를 넣어도 관리자 경계는 primary만 신뢰한다.
        redisTemplate.opsForValue().set(cacheKey, "ADMIN", Duration.ofSeconds(30));

        // 4. 회수 뒤 기존 연결의 새 SUBSCRIBE와 기존 구독의 새 push가 모두 차단된다.
        oldIdleSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(new LinkedBlockingQueue<>()));
        assertThat(oldIdleFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        dashboardWebSocketController.sendDashboardUpdate(sampleSummary(2L));
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> !oldSession.isConnected());
        assertThat(received.poll(NO_DELIVERY_MILLIS, TimeUnit.MILLISECONDS)).isNull();
        assertThat(activeReceived.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        // 5. 새 CONNECT도 회수된 관리자 구독을 만들 수 없고 scheduler를 호출할 필요가 없다.
        BlockingQueue<Throwable> newFailures = new LinkedBlockingQueue<>();
        newSession = connect(targetToken, newFailures);
        newSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(new LinkedBlockingQueue<>()));
        assertThat(newFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> !newSession.isConnected());
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isEqualTo("ADMIN");
    }

    private User createTestUser(String kind) {
        String email = "stomp-real-revoke-" + kind + "-" + UUID.randomUUID() + "@example.test";
        return userRepository.save(User.builder()
            .email(email)
            .passwordHash("unused-test-hash")
            .name("STOMP role test")
            .build());
    }

    private void grantAdmin(User user, Role adminRole) {
        userRoleRepository.save(UserRole.builder()
            .user(user)
            .role(adminRole)
            .assignedAt(LocalDateTime.now())
            .build());
    }

    private void deleteTestUser(Long userId) {
        userRoleRepository.deleteAll(userRoleRepository.findAllWithRoleByUserId(userId));
        userRepository.deleteById(userId);
    }

    private StompSession connect(String token, BlockingQueue<Throwable> failures) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync(
            "ws://localhost:" + port + "/ws/websocket",
            (WebSocketHttpHeaders) null,
            headers,
            new StompSessionHandlerAdapter() {
                @Override
                public void handleException(
                    StompSession session, StompCommand command, StompHeaders frameHeaders,
                    byte[] payload, Throwable exception
                ) {
                    failures.add(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    failures.add(exception);
                }
            }
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private StompFrameHandler dashboardFrames(BlockingQueue<DashboardSummaryResponse> received) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardSummaryResponse) payload);
            }
        };
    }

    private boolean hasDashboardSubscription(String email) {
        SimpUser user = simpUserRegistry.getUser(email);
        return user != null && user.getSessions().stream()
            .flatMap(session -> session.getSubscriptions().stream())
            .anyMatch(subscription -> DASHBOARD_TOPIC.equals(subscription.getDestination()));
    }

    private DashboardSummaryResponse sampleSummary(long total) {
        return new DashboardSummaryResponse(
            new DocumentsSummaryResponse(total, total, 0L),
            new JobsSummaryResponse(0L, 0L, 0L, 0L),
            new WorkersSummaryResponse(0L, 0L),
            new SearchSummaryResponse(0L)
        );
    }

    private String roleCacheKey(Long userId) {
        return "auth:roles:" + userId;
    }

    private String roleEpochKey(Long userId) {
        return "auth:roles:epoch:" + userId;
    }

    private void disconnect(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}
