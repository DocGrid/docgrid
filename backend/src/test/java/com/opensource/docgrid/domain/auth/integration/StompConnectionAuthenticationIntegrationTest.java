package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;

import io.jsonwebtoken.Claims;

/**
 * 실제 WebSocket 서버에 raw CONNECT·STOMP 프레임을 보내 두 명령이 동일한 JWT 및 블랙리스트
 * 인증 경계를 통과하는지 검증한다. Broker의 CONNECTED 응답만 보지 않고 {@link SimpUserRegistry}에
 * Principal이 등록됐는지 확인해 익명 연결을 인증 성공으로 오판하지 않는다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP 연결 인증 통합 테스트")
class StompConnectionAuthenticationIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String USER_EMAIL = "stomp-auth-user@example.com";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @MockitoBean
    private RoleAuthorityService roleAuthorityService;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-stomp-connection-authentication-integration-test-secret");
    }

    @Test
    @DisplayName("정상 케이스: 유효한 JWT를 담은 STOMP 명령은 인증 Principal을 세션에 등록한다")
    void authenticatesStompCommand_whenTokenValid() throws Exception {
        // Given
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("ADMIN"));
        String token = jwtProvider.generateToken(1L, USER_EMAIL);
        RawStompFrameHandler handler = new RawStompFrameHandler();
        WebSocketSession session = openSession(handler);

        try {
            // When
            session.sendMessage(new TextMessage(connectFrame(StompCommand.STOMP, token)));

            // Then — CONNECTED만으로는 익명 세션과 구분되지 않으므로 등록된 Principal까지 확인한다.
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> handler.hasFrameStartingWith("CONNECTED"));
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).untilAsserted(() -> {
                SimpUser user = simpUserRegistry.getUser(USER_EMAIL);
                assertThat(user).isNotNull();
                Principal principal = user.getPrincipal();
                assertThat(principal).isInstanceOf(Authentication.class);
            });
        } finally {
            closeSession(session);
        }
    }

    @Test
    @DisplayName("예외 케이스: 토큰 없는 STOMP 명령은 연결을 거부한다")
    void rejectsStompCommand_whenTokenMissing() throws Exception {
        // Given
        RawStompFrameHandler handler = new RawStompFrameHandler();
        WebSocketSession session = openSession(handler);

        try {
            // When
            session.sendMessage(new TextMessage(connectFrame(StompCommand.STOMP, null)));

            // Then
            awaitRejected(handler);
        } finally {
            closeSession(session);
        }
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("예외 케이스: 로그아웃 토큰은 두 연결 명령에서 모두 세션을 만들지 못한다")
    void rejectsConnection_whenTokenBlacklisted(StompCommand command) throws Exception {
        // Given
        String email = command.name().toLowerCase() + "-blacklisted@example.com";
        String token = jwtProvider.generateToken(2L, email);
        Claims claims = jwtProvider.getClaimsIfValid(token);
        tokenBlacklistService.blacklist(claims.get("jti", String.class), 60L);
        RawStompFrameHandler handler = new RawStompFrameHandler();
        WebSocketSession session = openSession(handler);

        try {
            // When
            session.sendMessage(new TextMessage(connectFrame(command, token)));

            // Then
            awaitRejected(handler);
            assertThat(simpUserRegistry.getUser(email)).isNull();
        } finally {
            closeSession(session);
        }
    }

    private WebSocketSession openSession(RawStompFrameHandler handler) throws Exception {
        return new StandardWebSocketClient()
            .execute(handler, new WebSocketHttpHeaders(), URI.create(webSocketUrl()))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void awaitRejected(RawStompFrameHandler handler) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> handler.hasFrameStartingWith("ERROR") || handler.isClosed()
                || handler.getTransportError() != null);
    }

    private void closeSession(WebSocketSession session) throws Exception {
        if (session.isOpen()) {
            session.close();
        }
    }

    private String webSocketUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private String connectFrame(StompCommand command, String token) {
        String authorization = token == null ? "" : "Authorization:Bearer " + token + "\n";
        return command.name()
            + "\naccept-version:1.2\n"
            + authorization
            + "heart-beat:0,0\n\n\0";
    }

    /**
     * 서버가 비동기로 전달하는 STOMP Text frame과 종료·전송 오류를 Thread-safe하게 수집한다.
     */
    private static final class RawStompFrameHandler extends TextWebSocketHandler {

        private final Queue<String> frames = new ConcurrentLinkedQueue<>();
        private final AtomicReference<Throwable> transportError = new AtomicReference<>();
        private volatile boolean closed;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            transportError.compareAndSet(null, exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }

        private boolean hasFrameStartingWith(String command) {
            return frames.stream().anyMatch(frame -> frame.startsWith(command));
        }

        private Throwable getTransportError() {
            return transportError.get();
        }

        private boolean isClosed() {
            return closed;
        }
    }
}
