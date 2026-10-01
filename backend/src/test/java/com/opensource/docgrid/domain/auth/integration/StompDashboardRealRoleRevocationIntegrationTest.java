package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyList;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.websocket.DashboardAuthorizationSnapshot;
import com.opensource.docgrid.domain.auth.websocket.StompDashboardOutboundAuthorizationInterceptor;
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
 * 새 구독과 기존 구독 push가 차단되는지 확인한다. 권한 확인을 마친 전송을 멈춰
 * 회수 응답 뒤에 도착할 수 있는 기존 진행 중 메시지도 별도로 재현한다.
 * GCP 복제본 라우팅은 범위 밖이다.
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

    @MockitoSpyBean
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

    @MockitoSpyBean
    private StompDashboardOutboundAuthorizationInterceptor outboundAuthorizationInterceptor;

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

    @Test
    @DisplayName("권한 확인을 마친 진행 중 메시지는 회수 응답 뒤에도 도착할 수 있다")
    void inFlightMessage_canArriveAfterRevocationResponse_whenAuthorizationFinishedFirst() throws Exception {
        // 1. 실제 DB 역할과 WebSocket 구독을 준비하고, 전송 직전의 대기 지점만 테스트용 spy로 제어한다.
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminCaller = createTestUser("race-caller");
        targetUser = createTestUser("race-target");
        grantAdmin(adminCaller, adminRole);
        grantAdmin(targetUser, adminRole);
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        oldSession = connect(
            jwtProvider.generateToken(targetUser.getId(), targetUser.getEmail()),
            new LinkedBlockingQueue<>()
        );
        oldSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(received));
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> hasDashboardSubscription(targetUser.getEmail()));

        CountDownLatch authorizationFinished = new CountDownLatch(1);
        CountDownLatch allowDelivery = new CountDownLatch(1);
        AtomicBoolean holdOneMessage = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Message<?> authorized = (Message<?>) invocation.callRealMethod();
            if (authorized != null
                && SimpMessageType.MESSAGE.equals(SimpMessageHeaderAccessor.getMessageType(authorized.getHeaders()))
                && DASHBOARD_TOPIC.equals(SimpMessageHeaderAccessor.getDestination(authorized.getHeaders()))
                && holdOneMessage.compareAndSet(true, false)) {
                authorizationFinished.countDown();
                if (!allowDelivery.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 전송 대기 시간이 초과됐습니다.");
                }
            }
            return authorized;
        }).when(outboundAuthorizationInterceptor).preSend(any(), any());

        CompletableFuture<Void> dispatch = CompletableFuture.runAsync(
            () -> dashboardWebSocketController.sendDashboardUpdate(sampleSummary(42L))
        );
        try {
            assertThat(authorizationFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(received.poll(NO_DELIVERY_MILLIS, TimeUnit.MILLISECONDS)).isNull();

            // 2. 이미 ADMIN으로 허용된 메시지를 붙잡은 동안 실제 HTTP 회수·DB 커밋을 완료한다.
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
            assertThat(received.poll(NO_DELIVERY_MILLIS, TimeUnit.MILLISECONDS)).isNull();

            // 3. 회수 응답 뒤에 전송을 풀어, 새로 허용된 전송과 기존 진행 중 전송을 구별한다.
            allowDelivery.countDown();
            dispatch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            DashboardSummaryResponse inFlightMessage = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(inFlightMessage).isNotNull();
            assertThat(inFlightMessage.documents().total()).isEqualTo(42L);
        } finally {
            allowDelivery.countDown();
            dispatch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Redis 무효화 실패로 낡은 ADMIN 캐시가 남아도 새 대시보드 push를 차단한다")
    @SuppressWarnings("unchecked")
    void redisInvalidationFailure_doesNotAuthorizeRevokedDashboardSession() throws Exception {
        // 1. 실제 구독과 캐시를 만들고 역할 무효화 Lua 호출만 시험에서 실패시킨다.
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        adminCaller = createTestUser("redis-failure-caller");
        targetUser = createTestUser("redis-failure-target");
        grantAdmin(adminCaller, adminRole);
        grantAdmin(targetUser, adminRole);
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        oldSession = connect(
            jwtProvider.generateToken(targetUser.getId(), targetUser.getEmail()),
            new LinkedBlockingQueue<>()
        );
        oldSession.subscribe(DASHBOARD_TOPIC, dashboardFrames(received));
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> hasDashboardSubscription(targetUser.getEmail()));
        redisTemplate.opsForValue().set(roleCacheKey(targetUser.getId()), "ADMIN", Duration.ofSeconds(30));
        doThrow(new IllegalStateException("simulated Redis invalidate failure"))
            .when(redisTemplate).execute(any(RedisScript.class), anyList());

        // 2. DB 커밋과 HTTP 200은 완료되지만 Redis의 이전 ADMIN 캐시는 그대로 남는다.
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
        assertThat(redisTemplate.opsForValue().get(roleCacheKey(targetUser.getId()))).isEqualTo("ADMIN");

        // 3. 다음 push는 Redis가 아닌 primary 일괄 조회를 사용하므로 메시지를 버린다.
        dashboardWebSocketController.sendDashboardUpdate(sampleSummary(43L));
        assertThat(received.poll(NO_DELIVERY_MILLIS, TimeUnit.MILLISECONDS)).isNull();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !oldSession.isConnected());
    }

    @Test
    @DisplayName("실제 발행 코드의 primary 판정은 outbound에 남고 STOMP 클라이언트에는 노출되지 않는다")
    void serverOnlyHeader_reachesOutboundButNotClient() throws Exception {
        // 1. 실제 구독을 만들고 수신자의 프레임 헤더와 서버 outbound 헤더를 따로 관측한다.
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        targetUser = createTestUser("internal-header");
        grantAdmin(targetUser, adminRole);
        BlockingQueue<StompHeaders> clientHeaders = new LinkedBlockingQueue<>();
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        oldSession = connect(
            jwtProvider.generateToken(targetUser.getId(), targetUser.getEmail()),
            new LinkedBlockingQueue<>()
        );
        oldSession.subscribe(DASHBOARD_TOPIC, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                clientHeaders.add(headers);
                received.add((DashboardSummaryResponse) payload);
            }
        });
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> hasDashboardSubscription(targetUser.getEmail()));

        AtomicReference<Object> outboundHeader = new AtomicReference<>();
        doAnswer(invocation -> {
            Message<?> message = invocation.getArgument(0);
            if (DASHBOARD_TOPIC.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                outboundHeader.set(message.getHeaders().get(DashboardAuthorizationSnapshot.HEADER));
            }
            return invocation.callRealMethod();
        }).when(outboundAuthorizationInterceptor).preSend(any(), any());

        // 2. 실제 발행 경로가 내부 판정을 만들고 브로커의 수신자별 MESSAGE까지 전파한다.
        dashboardWebSocketController.sendDashboardUpdate(sampleSummary(3L));

        // 3. outbound까지 전달됐더라도 클라이언트 프레임에서는 보이지 않아야 한다.
        assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(outboundHeader.get()).isInstanceOf(DashboardAuthorizationSnapshot.class);
        DashboardAuthorizationSnapshot snapshot = (DashboardAuthorizationSnapshot) outboundHeader.get();
        assertThat(snapshot.adminUserIds()).contains(targetUser.getId());
        assertThat(snapshot.candidateSessions()).containsValue(targetUser.getId());
        StompHeaders frame = clientHeaders.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame.getFirst(DashboardAuthorizationSnapshot.HEADER)).isNull();
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
