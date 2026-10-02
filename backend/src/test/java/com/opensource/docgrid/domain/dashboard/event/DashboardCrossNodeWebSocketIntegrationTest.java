package com.opensource.docgrid.domain.dashboard.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.opensource.docgrid.DocgridApplication;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.DocumentsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.JobsSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.SearchSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.WorkersSummaryResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;

/** 두 독립 Spring 앱의 실제 Redis 신호와 B의 STOMP 구독자 수신을 로컬에서 관통 검증한다. */
@Tag("integration")
@DisplayName("대시보드 A/B Spring 앱·WebSocket 통합 테스트")
class DashboardCrossNodeWebSocketIntegrationTest {

    private static final String TEST_JWT_SECRET = "docgrid-cross-node-websocket-integration-secret-2026";

    @Test
    @DisplayName("A/B 어느 쪽만 발행해도 반대편은 자기 DB 요약을 다시 계산해 자기 관리자에게 보낸다")
    void oneSidedPublish_reachesPeerSubscriberWithFreshSummary() throws Exception {
        String redisPort = System.getenv("DOCGRID_TEST_REDIS_PORT");
        assumeTrue(redisPort != null && System.getenv("DB_PORT") != null,
            "전용 로컬 Redis와 PostgreSQL이 없으면 두 앱 시험을 건너뜁니다.");

        try (ConfigurableApplicationContext a = startApp(redisPort)) {
            JdbcTemplate jdbc = a.getBean(JdbcTemplate.class);
            String email = "dashboard-cross-node-" + UUID.randomUUID() + "@example.invalid";
            Long userId = createAdmin(jdbc, email);
            try (ConfigurableApplicationContext b = startApp(redisPort)) {
                String token = a.getBean(JwtProvider.class).generateToken(userId, email);
                WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
                client.setMessageConverter(new MappingJackson2MessageConverter());
                StompSession aSession = null;
                StompSession bSession = null;
                try {
                    // 1. 두 독립 앱에 관리자 구독을 하나씩 만들고 로컬 브로커 등록을 확인한다.
                    BlockingQueue<DashboardSummaryResponse> aReceived = new LinkedBlockingQueue<>();
                    BlockingQueue<DashboardSummaryResponse> bReceived = new LinkedBlockingQueue<>();
                    aSession = subscribe(a, token, client, aReceived);
                    bSession = subscribe(b, token, client, bReceived);
                    // 최초 Redis 구독 callback의 일회성 새로고침을 비운 뒤 한쪽 발행만 관찰한다.
                    Thread.sleep(700);
                    aReceived.clear();
                    bReceived.clear();
                    assertThat(aReceived.poll(700, TimeUnit.MILLISECONDS)).isNull();
                    assertThat(bReceived.poll(700, TimeUnit.MILLISECONDS)).isNull();

                    // 2. A의 로컬 발행은 B에 갱신 신호만 보내고 B는 실제 DB에서 재계산한다.
                    DashboardSummaryResponse expectedB = b.getBean(DashboardQueryService.class).getSummary();
                    a.getBean(DashboardWebSocketController.class).sendDashboardUpdate(syntheticSummary());
                    DashboardSummaryResponse bResult = bReceived.poll(5, TimeUnit.SECONDS);
                    assertThat(bResult).isNotNull();
                    assertThat(bResult.documents()).isEqualTo(expectedB.documents());
                    assertThat(bResult.documents().total()).isNotEqualTo(999_999L);
                    aReceived.clear();

                    // 3. 발행 방향을 바꾸어도 A는 B의 합성 페이로드 대신 자기 DB 요약을 보낸다.
                    DashboardSummaryResponse expectedA = a.getBean(DashboardQueryService.class).getSummary();
                    b.getBean(DashboardWebSocketController.class).sendDashboardUpdate(syntheticSummary());
                    DashboardSummaryResponse aResult = aReceived.poll(5, TimeUnit.SECONDS);

                    assertThat(aResult).isNotNull();
                    assertThat(aResult.documents()).isEqualTo(expectedA.documents());
                    assertThat(aResult.documents().total()).isNotEqualTo(999_999L);
                } finally {
                    if (aSession != null && aSession.isConnected()) {
                        aSession.disconnect();
                    }
                    if (bSession != null && bSession.isConnected()) {
                        bSession.disconnect();
                    }
                    client.stop();
                }
            } finally {
                jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
            }
        }
    }

    private ConfigurableApplicationContext startApp(String redisPort) {
        return new SpringApplicationBuilder(DocgridApplication.class)
            .run("--spring.profiles.active=test", "--server.port=0", "--management.server.port=0",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=" + redisPort,
                "--jwt.secret=" + TEST_JWT_SECRET,
                "--dashboard.push.cross-node-enabled=true",
                "--indexing.worker.enabled=false", "--sync.dispatcher.enabled=false");
    }

    private Long createAdmin(JdbcTemplate jdbc, String email) {
        Long userId = jdbc.queryForObject(
            "INSERT INTO users (email, password_hash, name, status) VALUES (?, ?, ?, 'ACTIVE') RETURNING id",
            Long.class, email, "integration-test-only", "Dashboard Cross Node Test");
        jdbc.update("INSERT INTO user_roles (user_id, role_id, assigned_at) "
            + "VALUES (?, (SELECT id FROM roles WHERE code = 'ADMIN'), CURRENT_TIMESTAMP)", userId);
        return userId;
    }

    private StompSession subscribe(
        ConfigurableApplicationContext context,
        String token,
        WebSocketStompClient client,
        BlockingQueue<DashboardSummaryResponse> received
    ) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        StompSession session = client.connectAsync("ws://127.0.0.1:" + port + "/ws/websocket",
            (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() { })
            .get(5, TimeUnit.SECONDS);
        session.subscribe("/topic/dashboard", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return DashboardSummaryResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((DashboardSummaryResponse) payload);
            }
        });
        awaitSubscription(context.getBean(SimpUserRegistry.class));
        return session;
    }

    private void awaitSubscription(SimpUserRegistry registry) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!registry.findSubscriptions(subscription -> "/topic/dashboard".equals(
                subscription.getDestination())).isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("B의 관리자 대시보드 구독이 등록되지 않았습니다.");
    }

    private DashboardSummaryResponse syntheticSummary() {
        return new DashboardSummaryResponse(
            new DocumentsSummaryResponse(999_999L, 999_999L, 0),
            new JobsSummaryResponse(0, 0, 0, null),
            new WorkersSummaryResponse(0, 0),
            new SearchSummaryResponse(0)
        );
    }
}
