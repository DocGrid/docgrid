package com.opensource.docgrid.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRevalidationScheduler;
import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.entity.UserRole;
import com.opensource.docgrid.domain.user.repository.UserRoleRepository;

/**
 * 실제 WebSocket의 관리자 권한 회수 전후 경계를 신규 연결·새 구독·기존 구독으로 나눠 검증한다.
 *
 * <p>주기 검사를 자동 실행하지 않고 직접 호출해 검사 전 허용 창과 검사 후 물리 세션 종료를
 * 결정적으로 구분한다. 이 테스트는 즉시 회수를 구현하지 않으며 현재 제품 계약을 기록한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP 관리자 권한 회수 경계 통합 테스트")
class StompDashboardRoleRevocationIntegrationTest {

    private static final Long USER_ID = 92L;
    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final long TIMEOUT_SECONDS = 3;
    private static final long NO_DELIVERY_MILLIS = 300;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private StompSessionRevalidationScheduler revalidationScheduler;

    @Autowired
    private DashboardWebSocketController dashboardWebSocketController;

    @MockitoBean
    private RoleAuthorityService roleAuthorityService;

    @MockitoBean
    private UserRoleRepository userRoleRepository;

    private final AtomicReference<List<String>> currentRoles = new AtomicReference<>();
    private WebSocketStompClient stompClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "docgrid-stomp-dashboard-revocation-integration-test-secret-2026");
        registry.add("auth.stomp.session-revalidation.interval", () -> "1h");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        currentRoles.set(List.of("ADMIN"));
        given(roleAuthorityService.getRoles(USER_ID)).willAnswer(ignored -> currentRoles.get());
        given(userRoleRepository.findAllWithRoleByUserIdIn(anyList())).willAnswer(ignored -> currentRoles.get().stream()
            .map(roleCode -> userRole(roleCode))
            .toList());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    @DisplayName("회수 후 새 연결은 현재 역할로 인증되어 관리자 구독이 거부된다")
    void rejectsDashboardSubscription_whenConnectingAfterRevocation() throws Exception {
        String email = "dashboard-new-after-revoke@example.com";
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();

        // 1. 새 연결이 시작되기 전에 ADMIN을 회수한다.
        currentRoles.set(List.of("USER"));
        StompSession session = connect(email, failures);
        try {
            // 2. 새 Principal에는 ADMIN이 없으므로 대시보드 구독을 시도한다.
            session.subscribe(DASHBOARD_TOPIC, dashboardFrames(new LinkedBlockingQueue<>()));

            // 3. 실제 inbound 인가 거부와 브로커 미등록을 함께 확인한다.
            assertThat(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
            assertThat(hasDashboardSubscription(email)).isFalse();
        } finally {
            disconnect(session);
        }
    }

    @Test
    @DisplayName("기존 ADMIN 연결의 새 구독은 재검증 전 허용되고 재검증 후 세션이 종료된다")
    void closesOldSession_afterNewSubscriptionInRevalidationWindow() throws Exception {
        String email = "dashboard-old-new-subscribe@example.com";
        StompSession session = connect(email, new LinkedBlockingQueue<>());
        try {
            // 1. CONNECT 당시 저장된 ADMIN Principal을 유지한 채 DB 역할만 회수한다.
            currentRoles.set(List.of("USER"));

            // 2. 주기 검사가 아직 실행되지 않은 창의 실제 SUBSCRIBE 결과를 기록한다.
            session.subscribe(DASHBOARD_TOPIC, dashboardFrames(new LinkedBlockingQueue<>()));
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> hasDashboardSubscription(email));

            // 3. 재검증 후에는 물리 연결과 브로커 구독이 모두 제거되어야 한다.
            revalidationScheduler.revalidate();
            awaitSessionRemoval(email);
            assertThat(session.isConnected()).isFalse();
            assertThat(hasDashboardSubscription(email)).isFalse();
        } finally {
            disconnect(session);
        }
    }

    @Test
    @DisplayName("기존 관리자 구독은 재검증 전 push를 받지만 재검증 후에는 받지 않는다")
    void stopsDashboardPush_afterExistingSubscriptionIsRevalidated() throws Exception {
        String email = "dashboard-old-subscription@example.com";
        StompSession session = connect(email, new LinkedBlockingQueue<>());
        BlockingQueue<DashboardSummaryResponse> received = new LinkedBlockingQueue<>();
        try {
            // 1. 권한 회수 전에 ADMIN 구독이 실제 브로커에 등록된 상태를 만든다.
            session.subscribe(DASHBOARD_TOPIC, dashboardFrames(received));
            await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> hasDashboardSubscription(email));

            // 2. 검사 전에는 저장된 Principal 때문에 기존 구독으로 push가 도달한다.
            currentRoles.set(List.of("USER"));
            dashboardWebSocketController.sendDashboardUpdate(sampleSummary(1L));
            assertThat(received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();

            // 3. 재검증으로 연결을 닫은 뒤 보낸 새 push는 같은 세션에 도달하지 않는다.
            revalidationScheduler.revalidate();
            awaitSessionRemoval(email);
            dashboardWebSocketController.sendDashboardUpdate(sampleSummary(2L));
            assertThat(received.poll(NO_DELIVERY_MILLIS, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            disconnect(session);
        }
    }

    private StompSession connect(String email, BlockingQueue<Throwable> failures) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + jwtProvider.generateToken(USER_ID, email));
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

    private void awaitSessionRemoval(String email) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> simpUserRegistry.getUser(email) == null);
    }

    private DashboardSummaryResponse sampleSummary(long total) {
        return new DashboardSummaryResponse(
            new DocumentsSummaryResponse(total, total, 0L),
            new JobsSummaryResponse(0L, 0L, 0L, 0L),
            new WorkersSummaryResponse(0L, 0L),
            new SearchSummaryResponse(0L)
        );
    }

    private UserRole userRole(String roleCode) {
        User user = mock(User.class);
        Role role = mock(Role.class);
        UserRole userRole = mock(UserRole.class);
        given(user.getId()).willReturn(USER_ID);
        given(role.getCode()).willReturn(roleCode);
        given(userRole.getUser()).willReturn(user);
        given(userRole.getRole()).willReturn(role);
        return userRole;
    }

    private void disconnect(StompSession session) {
        if (session.isConnected()) {
            session.disconnect();
        }
    }
}
