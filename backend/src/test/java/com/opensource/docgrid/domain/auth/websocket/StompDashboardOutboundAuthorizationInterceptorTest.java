package com.opensource.docgrid.domain.auth.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 브로커가 수신자별로 복제한 대시보드 메시지를 push별 primary 판정과 대조한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 대시보드 outbound 인가 단위 테스트")
class StompDashboardOutboundAuthorizationInterceptorTest {

    @Mock
    private StompSessionRegistry stompSessionRegistry;

    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompDashboardOutboundAuthorizationInterceptor interceptor;

    @Test
    @DisplayName("push별 primary 판정에 ADMIN이면 기존 구독으로 보낸 메시지를 허용한다")
    void preSend_allowsDashboardMessage_whenStillAdmin() {
        Message<byte[]> message = message("/topic/dashboard", "session-1", snapshot(Set.of(1L)));
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        then(stompSessionRegistry).should(never()).close("session-1");
    }

    @Test
    @DisplayName("회수 뒤에는 기존 구독 메시지를 버리고 물리 세션을 닫는다")
    void preSend_dropsDashboardMessage_whenAdminWasRevoked() {
        Message<byte[]> message = message("/topic/dashboard", "session-1", snapshot(Set.of()));
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should().close("session-1");
    }

    @Test
    @DisplayName("primary 판정 헤더가 없으면 이전 CONNECT 권한으로 통과시키지 않는다")
    void preSend_dropsDashboardMessage_whenDecisionIsMissing() {
        Message<byte[]> message = message("/topic/dashboard", "session-1", null);
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should(never()).close("session-1");
    }

    @Test
    @DisplayName("판정 수집 뒤 연결된 ADMIN 세션은 이번 메시지만 건너뛰고 연결을 유지한다")
    void preSend_dropsLateSessionWithoutClosingIt() {
        Message<byte[]> message = message("/topic/dashboard", "session-1",
            new DashboardAuthorizationSnapshot(Map.of(), Set.of()));
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should(never()).close("session-1");
    }

    @Test
    @DisplayName("같은 사용자라도 판정 뒤 새로 연결한 세션에는 이 push를 전달하지 않는다")
    void preSend_dropsLateSessionOfAlreadyApprovedUser() {
        Message<byte[]> message = message("/topic/dashboard", "session-late", snapshot(Set.of(1L)));
        given(stompSessionRegistry.authorizationFor("session-late")).willReturn(authorization());

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should(never()).close("session-late");
    }

    @Test
    @DisplayName("추적되지 않은 세션과 sessionId 없는 대시보드 메시지는 전달하지 않는다")
    void preSend_dropsDashboardMessage_whenSessionIsUnknown() {
        assertThat(interceptor.preSend(message("/topic/dashboard", "unknown", snapshot(Set.of(1L))), channel)).isNull();
        then(stompSessionRegistry).should().close("unknown");

        assertThat(interceptor.preSend(message("/topic/dashboard", null, snapshot(Set.of(1L))), channel)).isNull();
    }

    @Test
    @DisplayName("대시보드 외 메시지는 추가 DB 조회 없이 통과한다")
    void preSend_passesOtherDestinations() {
        Message<byte[]> message = message("/queue/rag-answer", "session-1", null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        then(stompSessionRegistry).shouldHaveNoInteractions();
    }

    private StompSessionAuthorization authorization() {
        return new StompSessionAuthorization(1L, "jti-1", Instant.now().plusSeconds(60), Set.of("ADMIN"));
    }

    private DashboardAuthorizationSnapshot snapshot(Set<Long> admins) {
        return new DashboardAuthorizationSnapshot(Map.of("session-1", 1L), admins);
    }

    private Message<byte[]> message(String destination, String sessionId, DashboardAuthorizationSnapshot snapshot) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        if (snapshot != null) {
            accessor.setHeader(DashboardAuthorizationSnapshot.HEADER, snapshot);
        }
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
