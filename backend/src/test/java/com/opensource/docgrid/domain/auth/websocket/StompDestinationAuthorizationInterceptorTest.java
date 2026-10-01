package com.opensource.docgrid.domain.auth.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.security.Principal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;

/**
 * STOMP client가 사용할 수 있는 정확한 구독 목적지와 발행 금지 정책을 단위 수준에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 목적지 인가 Interceptor 단위 테스트")
class StompDestinationAuthorizationInterceptorTest {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final String RAG_ANSWER_QUEUE = "/user/queue/rag-answer";

    @Mock
    private MessageChannel channel;

    @Mock
    private RoleAuthorityService roleAuthorityService;

    @InjectMocks
    private StompDestinationAuthorizationInterceptor interceptor;

    @Test
    @DisplayName("정상 케이스: ADMIN은 정확한 dashboard 목적지를 구독할 수 있다")
    void preSend_allowsDashboardSubscription_whenAdmin() {
        // Given
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, DASHBOARD_TOPIC, admin());
        given(roleAuthorityService.getRolesForAdmin(1L)).willReturn(List.of("ADMIN"));

        // When
        Message<?> result = interceptor.preSend(message, channel);

        // Then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("정상 케이스: 인증 사용자는 정확한 RAG 개인 알림 목적지를 구독할 수 있다")
    void preSend_allowsRagSubscription_whenAuthenticated() {
        // Given
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, RAG_ANSWER_QUEUE, user());

        // When
        Message<?> result = interceptor.preSend(message, channel);

        // Then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("예외 케이스: ADMIN이 아닌 사용자는 정확한 dashboard 목적지도 구독할 수 없다")
    void preSend_rejectsDashboardSubscription_whenNotAdmin() {
        // Given
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, DASHBOARD_TOPIC, user());

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("예외 케이스: 연결 당시 ADMIN이어도 현재 primary에서 회수되었으면 새 구독을 거부한다")
    void preSend_rejectsDashboardSubscription_whenAdminWasRevoked() {
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, DASHBOARD_TOPIC, admin());
        given(roleAuthorityService.getRolesForAdmin(1L)).willReturn(List.of("USER"));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("예외 케이스: primary 역할을 확인할 수 없으면 이전 ADMIN 권한으로 구독하지 않는다")
    void preSend_rejectsDashboardSubscription_whenPrimaryIsUnavailable() {
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, DASHBOARD_TOPIC, admin());
        given(roleAuthorityService.getRolesForAdmin(1L)).willThrow(new IllegalStateException("primary unavailable"));

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("예외 케이스: Principal이 없으면 RAG 개인 알림 목적지를 구독할 수 없다")
    void preSend_rejectsRagSubscription_whenPrincipalMissing() {
        // Given
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, RAG_ANSWER_QUEUE, null);

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "/topic/*",
        "/topic/**",
        "/topic*/**",
        "/topic/dash{x}",
        "/queue/*",
        "/queue/rag-answer-user-other-session",
        "/topic/other",
        "/user/queue/other"
    })
    @DisplayName("예외 케이스: 허용 목록 밖의 정확한 목적지·pattern·내부 queue는 구독할 수 없다")
    void preSend_rejectsSubscription_whenDestinationNotAllowed(String destination) {
        // Given — ADMIN도 넓은 목적지를 구독할 수 없게 해 역할과 목적지 정책을 분리한다.
        Message<byte[]> message = message(StompCommand.SUBSCRIBE, destination, admin());

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        DASHBOARD_TOPIC,
        RAG_ANSWER_QUEUE,
        "/topic/other",
        "/queue/rag-answer-user-other-session"
    })
    @DisplayName("예외 케이스: 서버만 push를 발행하므로 client SEND는 목적지와 무관하게 거부한다")
    void preSend_rejectsEveryClientSend(String destination) {
        // Given
        Message<byte[]> message = message(StompCommand.SEND, destination, admin());

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("예외 케이스: client가 서버 전용 MESSAGE 명령을 보내도 발행으로 보고 거부한다")
    void preSend_rejectsServerOnlyMessageCommand_fromClient() {
        // Given — Spring은 SEND와 MESSAGE를 모두 SimpMessageType.MESSAGE로 변환한다.
        Message<byte[]> message = message(StompCommand.MESSAGE, RAG_ANSWER_QUEUE, user());

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(message, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("정상 케이스: destination 인가 대상이 아닌 DISCONNECT는 그대로 통과한다")
    void preSend_passesDisconnect() {
        // Given
        Message<byte[]> message = message(StompCommand.DISCONNECT, null, user());

        // When
        Message<?> result = interceptor.preSend(message, channel);

        // Then
        assertThat(result).isSameAs(message);
    }

    private Message<byte[]> message(StompCommand command, String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Principal admin() {
        return authentication("admin@example.com", "ROLE_ADMIN");
    }

    private Principal user() {
        return authentication("user@example.com", "ROLE_USER");
    }

    private Principal authentication(String email, String authority) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            email,
            null,
            List.of(new SimpleGrantedAuthority(authority))
        );
        authentication.setDetails(1L);
        return authentication;
    }
}
