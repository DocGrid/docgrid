package com.opensource.docgrid.domain.auth.integration;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

import com.fasterxml.jackson.databind.JsonNode;

import io.jsonwebtoken.Claims;

/**
 * 실제 WebSocket 연결이 CONNECT 이후의 token 폐기 상태까지 반영해 broker 구독에서 제거되는지 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP 세션 수명주기 통합 테스트")
class StompSessionLifecycleIntegrationTest {

    private static final Long USER_ID = 91L;
    private static final String USER_EMAIL = "stomp-lifecycle-user@example.com";
    private static final String RAG_ANSWER_QUEUE = "/user/queue/rag-answer";
    private static final long TIMEOUT_SECONDS = 3;
    private static final String JWT_SECRET = "docgrid-stomp-session-lifecycle-integration-test-secret-2026";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private RagWebSocketController ragWebSocketController;

    @MockitoBean
    private RoleAuthorityService roleAuthorityService;

    @MockitoBean
    private UserRoleRepository userRoleRepository;

    private final AtomicReference<List<String>> currentRoles = new AtomicReference<>();
    private WebSocketStompClient stompClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("auth.stomp.session-revalidation.interval", () -> "100ms");
        // 전체 테스트 실행에서 Spring context별 idle connection 누적이 PostgreSQL 한도를 잠식하지 않게 제한한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        currentRoles.set(List.of("USER"));
        given(roleAuthorityService.getRoles(USER_ID)).willAnswer(ignored -> currentRoles.get());
        given(userRoleRepository.findAllWithRoleByUserIdIn(anyList())).willAnswer(ignored -> currentRoles.get().stream()
            .map(roleCode -> userRole(USER_ID, roleCode))
            .toList());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    @DisplayName("연결 뒤 token을 blacklist에 등록하면 기존 구독 세션도 종료한다")
    void closesExistingSession_whenConnectedTokenBecomesBlacklisted() throws Exception {
        // Given
        String token = jwtProvider.generateToken(USER_ID, USER_EMAIL);
        Claims claims = jwtProvider.getClaimsIfValid(token);
        StompSession session = connect(token);
        BlockingQueue<JsonNode> events = subscribeRag(session);
        awaitSubscription();

        try {
            // When
            tokenBlacklistService.blacklist(claims.get("jti", String.class), 60L);

            // Then
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> simpUserRegistry.getUser(USER_EMAIL) == null);
            ragWebSocketController.notifyAnswerReady(USER_EMAIL, 42L);
            assertThat(events.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    @DisplayName("연결 뒤 JWT가 만료되면 기존 구독 세션을 종료한다")
    void closesExistingSession_whenTokenExpires() throws Exception {
        // Given
        JwtProvider shortLivedJwtProvider = new JwtProvider(JWT_SECRET, 1L);
        StompSession session = connect(shortLivedJwtProvider.generateToken(USER_ID, USER_EMAIL));
        subscribeRag(session);
        awaitSubscription();

        try {
            // When & Then
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> simpUserRegistry.getUser(USER_EMAIL) == null);
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    @DisplayName("연결 뒤 역할이 변경되면 오래된 Principal을 가진 기존 세션을 종료한다")
    void closesExistingSession_whenRolesChange() throws Exception {
        // Given
        currentRoles.set(List.of("USER", "ADMIN"));
        StompSession session = connect(jwtProvider.generateToken(USER_ID, USER_EMAIL));
        subscribeRag(session);
        awaitSubscription();

        try {
            // When
            currentRoles.set(List.of("USER"));

            // Then
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> simpUserRegistry.getUser(USER_EMAIL) == null);
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    @DisplayName("인증 상태가 그대로인 세션은 여러 검사 주기 뒤에도 push를 받는다")
    void keepsSessionAndDeliversPush_whenAuthorizationRemainsValid() throws Exception {
        // Given
        StompSession session = connect(jwtProvider.generateToken(USER_ID, USER_EMAIL));
        BlockingQueue<JsonNode> events = subscribeRag(session);
        awaitSubscription();

        try {
            // When — 100ms 검사 주기를 여러 번 지난 뒤 서버 push를 보낸다.
            await().pollDelay(Duration.ofMillis(500)).until(() -> true);
            ragWebSocketController.notifyAnswerReady(USER_EMAIL, 77L);

            // Then
            JsonNode event = events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.path("queryId").asLong()).isEqualTo(77L);
            assertThat(simpUserRegistry.getUser(USER_EMAIL)).isNotNull();
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient
            .connectAsync(
                webSocketUrl(),
                (WebSocketHttpHeaders) null,
                connectHeaders,
                new StompSessionHandlerAdapter() { }
            )
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribeRag(StompSession session) {
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        session.subscribe(RAG_ANSWER_QUEUE, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add((JsonNode) payload);
            }
        });
        return events;
    }

    private void awaitSubscription() {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> {
            SimpUser user = simpUserRegistry.getUser(USER_EMAIL);
            return user != null && user.getSessions().stream()
                .flatMap(session -> session.getSubscriptions().stream())
                .anyMatch(subscription -> RAG_ANSWER_QUEUE.equals(subscription.getDestination()));
        });
    }

    private String webSocketUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private UserRole userRole(Long userId, String roleCode) {
        User user = mock(User.class);
        Role role = mock(Role.class);
        UserRole userRole = mock(UserRole.class);
        given(user.getId()).willReturn(userId);
        given(role.getCode()).willReturn(roleCode);
        given(userRole.getUser()).willReturn(user);
        given(userRole.getRole()).willReturn(role);
        return userRole;
    }
}
