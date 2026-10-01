package com.opensource.docgrid.domain.auth.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
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

import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;

/**
 * 브로커가 기존 구독자에게 보내는 대시보드 메시지의 전달 직전 권한 판단을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 대시보드 outbound 인가 단위 테스트")
class StompDashboardOutboundAuthorizationInterceptorTest {

    @Mock
    private StompSessionRegistry stompSessionRegistry;

    @Mock
    private RoleAuthorityService roleAuthorityService;

    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompDashboardOutboundAuthorizationInterceptor interceptor;

    @Test
    @DisplayName("현재 primary에서도 ADMIN이면 기존 구독으로 보낸 메시지를 허용한다")
    void preSend_allowsDashboardMessage_whenStillAdmin() {
        Message<byte[]> message = message("/topic/dashboard", "session-1");
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());
        given(roleAuthorityService.getRolesForAdmin(1L)).willReturn(List.of("ADMIN"));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        then(stompSessionRegistry).should(never()).close("session-1");
    }

    @Test
    @DisplayName("회수 뒤에는 기존 구독 메시지를 버리고 물리 세션을 닫는다")
    void preSend_dropsDashboardMessage_whenAdminWasRevoked() {
        Message<byte[]> message = message("/topic/dashboard", "session-1");
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());
        given(roleAuthorityService.getRolesForAdmin(1L)).willReturn(List.of("USER"));

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should().close("session-1");
    }

    @Test
    @DisplayName("primary 역할 확인 실패도 기존 권한으로 통과시키지 않는다")
    void preSend_dropsDashboardMessage_whenPrimaryIsUnavailable() {
        Message<byte[]> message = message("/topic/dashboard", "session-1");
        given(stompSessionRegistry.authorizationFor("session-1")).willReturn(authorization());
        given(roleAuthorityService.getRolesForAdmin(1L)).willThrow(new IllegalStateException("primary unavailable"));

        assertThat(interceptor.preSend(message, channel)).isNull();
        then(stompSessionRegistry).should().close("session-1");
    }

    @Test
    @DisplayName("추적되지 않은 세션과 sessionId 없는 대시보드 메시지는 전달하지 않는다")
    void preSend_dropsDashboardMessage_whenSessionIsUnknown() {
        assertThat(interceptor.preSend(message("/topic/dashboard", "unknown"), channel)).isNull();
        then(stompSessionRegistry).should().close("unknown");

        assertThat(interceptor.preSend(message("/topic/dashboard", null), channel)).isNull();
    }

    @Test
    @DisplayName("대시보드 외 메시지는 추가 DB 조회 없이 통과한다")
    void preSend_passesOtherDestinations() {
        Message<byte[]> message = message("/queue/rag-answer", "session-1");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    private StompSessionAuthorization authorization() {
        return new StompSessionAuthorization(1L, "jti-1", Instant.now().plusSeconds(60), Set.of("ADMIN"));
    }

    private Message<byte[]> message(String destination, String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
