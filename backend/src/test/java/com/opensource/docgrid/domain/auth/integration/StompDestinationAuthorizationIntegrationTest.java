package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;

/**
 * 실제 WebSocket과 SimpleBroker를 사용해 STOMP destination 허용 목록, 사용자 queue 격리,
 * client SEND 금지가 inbound interceptor부터 메시지 전달까지 유지되는지 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP 목적지 인가 통합 테스트")
class StompDestinationAuthorizationIntegrationTest {

    private static final String RAG_ANSWER_QUEUE = "/user/queue/rag-answer";
    private static final long TIMEOUT_SECONDS = 5;
    private static final long NO_DELIVERY_TIMEOUT_MILLIS = 500;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private RagWebSocketController ragWebSocketController;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    private WebSocketStompClient stompClient;

    @DynamicPropertySource
    static void configureJwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-stomp-destination-authorization-integration-test-secret-2026");
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    @DisplayName("정상 케이스: 정확한 RAG 목적지를 구독한 사용자 중 대상 사용자만 완료 이벤트를 받는다")
    void deliversRagEventOnlyToTargetUser_whenExactDestinationSubscribed() throws Exception {
        // Given
        String targetEmail = "rag-target@example.com";
        String otherEmail = "rag-other@example.com";
        BlockingQueue<Throwable> targetFailures = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> otherFailures = new LinkedBlockingQueue<>();
        StompSession targetSession = connect(userToken(targetEmail), targetFailures);
        StompSession otherSession = connect(userToken(otherEmail), otherFailures);
        BlockingQueue<JsonNode> targetEvents = subscribeRag(targetSession);
        BlockingQueue<JsonNode> otherEvents = subscribeRag(otherSession);
        awaitSubscription(targetEmail, RAG_ANSWER_QUEUE);
        awaitSubscription(otherEmail, RAG_ANSWER_QUEUE);

        try {
            // When
            ragWebSocketController.notifyAnswerReady(targetEmail, 42L);

            // Then
            JsonNode event = targetEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.path("queryId").asLong()).isEqualTo(42L);
            assertThat(otherEvents.poll(NO_DELIVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isNull();
            assertThat(targetFailures).isEmpty();
            assertThat(otherFailures).isEmpty();
        } finally {
            disconnect(targetSession);
            disconnect(otherSession);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/**", "/queue/*"})
    @DisplayName("예외 케이스: SimpleBroker가 해석할 수 있는 pattern 구독도 연결 단계에서 거부한다")
    void rejectsPatternSubscription(String destination) throws Exception {
        // Given
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        StompSession session = connect(userToken("pattern-user@example.com"), failures);

        try {
            // When
            session.subscribe(destination, discardingFrameHandler());

            // Then
            assertThat(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        } finally {
            disconnect(session);
        }
    }

    @Test
    @DisplayName("예외 케이스: client가 다른 사용자의 RAG queue로 SEND하면 거부되고 전달되지 않는다")
    void rejectsClientSend_toAnotherUserQueue() throws Exception {
        // Given
        String targetEmail = "send-target@example.com";
        BlockingQueue<Throwable> targetFailures = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> senderFailures = new LinkedBlockingQueue<>();
        StompSession targetSession = connect(userToken(targetEmail), targetFailures);
        StompSession senderSession = connect(userToken("send-attacker@example.com"), senderFailures);
        BlockingQueue<JsonNode> targetEvents = subscribeRag(targetSession);
        awaitSubscription(targetEmail, RAG_ANSWER_QUEUE);

        try {
            // When
            senderSession.send(
                "/user/" + targetEmail + "/queue/rag-answer",
                Map.of("queryId", 99L)
            );

            // Then
            assertThat(senderFailures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
            assertThat(targetEvents.poll(NO_DELIVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isNull();
            assertThat(targetFailures).isEmpty();
        } finally {
            disconnect(targetSession);
            disconnect(senderSession);
        }
    }

    private StompSession connect(String token, BlockingQueue<Throwable> failures) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        try {
            return stompClient
                .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, failureHandler(failures))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw new AssertionError("STOMP 연결에 실패했습니다.", exception.getCause());
        }
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

    private void awaitSubscription(String email, String destination) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> {
            SimpUser user = simpUserRegistry.getUser(email);
            return user != null && user.getSessions().stream()
                .flatMap(session -> session.getSubscriptions().stream())
                .anyMatch(subscription -> destination.equals(subscription.getDestination()));
        });
    }

    private StompFrameHandler discardingFrameHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 인가 실패가 기대 결과이므로 수신 payload는 사용하지 않는다.
            }
        };
    }

    private StompSessionHandlerAdapter failureHandler(BlockingQueue<Throwable> failures) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleException(
                StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception
            ) {
                failures.add(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                failures.add(exception);
            }
        };
    }

    private String userToken(String email) {
        return jwtProvider.generateToken(2L, email);
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private void disconnect(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}
