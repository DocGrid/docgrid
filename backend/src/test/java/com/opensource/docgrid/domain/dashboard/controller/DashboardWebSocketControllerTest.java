package com.opensource.docgrid.domain.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.opensource.docgrid.domain.auth.service.query.PrimaryRoleQueryService;
import com.opensource.docgrid.domain.auth.websocket.DashboardAuthorizationSnapshot;
import com.opensource.docgrid.domain.auth.websocket.StompSessionAuthorization;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry.SessionSnapshot;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeSignal;

/**
 * 대시보드 발행 직전에 로컬 후보를 중복 제거하고 push별 primary 판정만 전달하는 경계를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("대시보드 WebSocket 발행 권한 일괄 조회 단위 테스트")
class DashboardWebSocketControllerTest {

    @InjectMocks private DashboardWebSocketController controller;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StompSessionRegistry stompSessionRegistry;
    @Mock private PrimaryRoleQueryService primaryRoleQueryService;
    @Mock private DashboardCrossNodeSignal dashboardCrossNodeSignal;

    @Test
    @DisplayName("같은 사용자의 여러 세션은 primary에서 한 번만 확인하고 내부 헤더에만 판정을 넣는다")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sendDashboardUpdate_deduplicatesUsersAndAttachesDecision() {
        DashboardSummaryResponse summary = org.mockito.Mockito.mock(DashboardSummaryResponse.class);
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(
            session("session-1", 1L, "ADMIN"),
            session("session-2", 1L, "ADMIN"),
            session("session-3", 2L, "USER")
        ));
        given(primaryRoleQueryService.findCurrentAdminUserIds(List.of(1L))).willReturn(Set.of(1L));
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);

        controller.sendDashboardUpdate(summary);

        then(primaryRoleQueryService).should().findCurrentAdminUserIds(List.of(1L));
        then(messagingTemplate).should().convertAndSend(eq("/topic/dashboard"), same(summary), headers.capture());
        Object decision = headers.getValue().get(DashboardAuthorizationSnapshot.HEADER);
        assertThat(decision).isInstanceOf(DashboardAuthorizationSnapshot.class);
        DashboardAuthorizationSnapshot snapshot = (DashboardAuthorizationSnapshot) decision;
        assertThat(snapshot.candidateSessions()).isEqualTo(Map.of("session-1", 1L, "session-2", 1L));
        assertThat(snapshot.adminUserIds()).isEqualTo(Set.of(1L));
        assertThat(headers.getValue()).doesNotContainKey("nativeHeaders");
        then(dashboardCrossNodeSignal).should().publish();
    }

    @Test
    @DisplayName("ADMIN 후보가 없으면 primary 트랜잭션을 열지 않는다")
    void sendDashboardUpdate_skipsPrimaryWhenNoAdminCandidate() {
        DashboardSummaryResponse summary = org.mockito.Mockito.mock(DashboardSummaryResponse.class);
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of());

        controller.sendDashboardUpdate(summary);

        then(primaryRoleQueryService).shouldHaveNoInteractions();
        then(messagingTemplate).should().convertAndSend(eq("/topic/dashboard"), same(summary), any(Map.class));
        then(dashboardCrossNodeSignal).should().publish();
    }

    @Test
    @DisplayName("primary 판정 실패 시 이전 ADMIN 권한으로 발행하지 않는다")
    void sendDashboardUpdate_doesNotPublishWhenPrimaryFails() {
        DashboardSummaryResponse summary = org.mockito.Mockito.mock(DashboardSummaryResponse.class);
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of(session("session-1", 1L, "ADMIN")));
        given(primaryRoleQueryService.findCurrentAdminUserIds(List.of(1L)))
            .willThrow(new IllegalStateException("primary unavailable"));

        assertThatThrownBy(() -> controller.sendDashboardUpdate(summary))
            .isInstanceOf(IllegalStateException.class);
        then(messagingTemplate).shouldHaveNoInteractions();
        then(dashboardCrossNodeSignal).should().publish();
    }

    @Test
    @DisplayName("원격 갱신은 로컬 관리자에게만 보내고 Redis로 다시 발행하지 않는다")
    void sendLocalDashboardUpdate_doesNotRepublish() {
        DashboardSummaryResponse summary = org.mockito.Mockito.mock(DashboardSummaryResponse.class);
        given(stompSessionRegistry.authenticatedSessions()).willReturn(List.of());

        controller.sendLocalDashboardUpdate(summary);

        then(messagingTemplate).should().convertAndSend(eq("/topic/dashboard"), same(summary), any(Map.class));
        then(dashboardCrossNodeSignal).shouldHaveNoInteractions();
    }

    private SessionSnapshot session(String sessionId, Long userId, String role) {
        return new SessionSnapshot(sessionId,
            new StompSessionAuthorization(userId, "test-jti", Instant.now().plusSeconds(60), Set.of(role)));
    }
}
