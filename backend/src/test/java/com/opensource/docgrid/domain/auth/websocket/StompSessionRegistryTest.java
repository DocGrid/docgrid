package com.opensource.docgrid.domain.auth.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 물리 WebSocket 연결과 인증 snapshot의 결합, 종료 및 실패 시 재시도 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 세션 Registry 단위 테스트")
class StompSessionRegistryTest {

    private static final String SESSION_ID = "session-1";

    @Mock
    private WebSocketSession session;

    private final StompSessionRegistry registry = new StompSessionRegistry();

    @Test
    @DisplayName("물리 연결과 인증 snapshot이 모두 등록된 세션만 검사 대상으로 반환한다")
    void authenticatedSessions_returnsOnlyAuthenticatedOpenSessions() {
        // Given
        given(session.getId()).willReturn(SESSION_ID);
        given(session.isOpen()).willReturn(true);
        StompSessionAuthorization authorization = authorization();

        // When
        registry.registerTransport(session);
        boolean authenticated = registry.authenticate(SESSION_ID, authorization);

        // Then
        assertThat(authenticated).isTrue();
        assertThat(registry.authenticatedSessions())
            .containsExactly(new StompSessionRegistry.SessionSnapshot(SESSION_ID, authorization));
        assertThat(registry.authenticatedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("물리 연결이 없는 sessionId에는 인증 snapshot을 등록하지 않는다")
    void authenticate_returnsFalse_whenTransportMissing() {
        assertThat(registry.authenticate(SESSION_ID, authorization())).isFalse();
        assertThat(registry.authenticatedSessions()).isEmpty();
    }

    @Test
    @DisplayName("유효하지 않은 세션을 정책 위반 상태로 닫고 registry에서 제거한다")
    void close_closesTransportAndRemovesSession() throws Exception {
        // Given
        given(session.getId()).willReturn(SESSION_ID);
        given(session.isOpen()).willReturn(true);
        registry.registerTransport(session);
        registry.authenticate(SESSION_ID, authorization());

        // When
        boolean closed = registry.close(SESSION_ID);

        // Then
        assertThat(closed).isTrue();
        then(session).should().close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.authenticatedSessions()).isEmpty();
    }

    @Test
    @DisplayName("세션 종료가 실패하면 추적 정보를 유지해 다음 검사에서 재시도할 수 있다")
    void close_keepsSession_whenTransportCloseFails() throws Exception {
        // Given
        given(session.getId()).willReturn(SESSION_ID);
        given(session.isOpen()).willReturn(true);
        registry.registerTransport(session);
        registry.authenticate(SESSION_ID, authorization());
        org.mockito.BDDMockito.willThrow(new IOException("close failed"))
            .given(session).close(CloseStatus.POLICY_VIOLATION);

        // When
        boolean closed = registry.close(SESSION_ID);

        // Then
        assertThat(closed).isFalse();
        assertThat(registry.authenticatedSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 물리 sessionId를 중복 등록하면 기존 연결을 덮어쓰지 않는다")
    void registerTransport_rejectsDuplicateSessionId() {
        // Given
        given(session.getId()).willReturn(SESSION_ID);
        registry.registerTransport(session);

        // When & Then
        assertThatThrownBy(() -> registry.registerTransport(session))
            .isInstanceOf(IllegalStateException.class);
    }

    private StompSessionAuthorization authorization() {
        return new StompSessionAuthorization(
            1L,
            "jti-1",
            Instant.parse("2026-09-28T00:00:00Z"),
            Set.of("USER")
        );
    }
}
