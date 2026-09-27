package com.opensource.docgrid.domain.auth.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import lombok.extern.slf4j.Slf4j;

/**
 * 현재 Backend 인스턴스가 소유한 물리 WebSocket 연결과 STOMP 인증 snapshot을 함께 관리한다.
 *
 * <p>물리 연결은 WebSocket decorator가 먼저 등록하고, CONNECT 인증이 끝난 뒤 같은 sessionId에
 * 인증 snapshot을 결합한다. 주기 검사는 인증 완료 세션만 읽으며, 종료와 인증이 경합해도 하나의
 * ConcurrentMap entry를 기준으로 정리해 닫힌 연결이 다시 등록되는 것을 막는다.
 *
 * <p>이 registry는 로컬 전송 자원만 관리한다. 여러 Backend 인스턴스는 각자 자신의 registry를
 * 검사하고 공통 Redis·DB 상태를 읽으므로 분산 session registry나 lock이 필요하지 않다.
 */
@Component
@Slf4j
public class StompSessionRegistry {

    private static final CloseStatus AUTHORIZATION_INVALID = CloseStatus.POLICY_VIOLATION;

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public void registerTransport(WebSocketSession session) {
        SessionState previous = sessions.putIfAbsent(session.getId(), new SessionState(session));
        if (previous != null) {
            throw new IllegalStateException("이미 등록된 WebSocket sessionId입니다.");
        }
    }

    public boolean authenticate(String sessionId, StompSessionAuthorization authorization) {
        return sessions.computeIfPresent(sessionId, (ignored, state) -> {
            state.authenticate(authorization);
            return state;
        }) != null;
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public List<SessionSnapshot> authenticatedSessions() {
        List<SessionSnapshot> snapshots = new ArrayList<>();
        sessions.forEach((sessionId, state) -> {
            if (!state.session().isOpen()) {
                sessions.remove(sessionId, state);
                return;
            }
            StompSessionAuthorization authorization = state.authorization();
            if (authorization != null) {
                snapshots.add(new SessionSnapshot(sessionId, authorization));
            }
        });
        return List.copyOf(snapshots);
    }

    public int authenticatedSessionCount() {
        return (int) sessions.values().stream()
            .filter(state -> state.session().isOpen() && state.authorization() != null)
            .count();
    }

    public boolean close(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        if (!state.session().isOpen()) {
            sessions.remove(sessionId, state);
            return false;
        }

        try {
            state.session().close(AUTHORIZATION_INVALID);
            sessions.remove(sessionId, state);
            return true;
        } catch (IOException | RuntimeException exception) {
            // 추적 정보를 남겨 다음 검사에서 다시 닫을 수 있게 한다. 식별 정보는 로그에 노출하지 않는다.
            log.warn("유효하지 않은 STOMP WebSocket 세션 종료에 실패했습니다: {}", exception.getMessage());
            return false;
        }
    }

    /** 주기 검사에 필요한 sessionId와 불변 인증 snapshot만 노출하는 조회 경계다. */
    public record SessionSnapshot(String sessionId, StompSessionAuthorization authorization) {
    }

    /** 물리 연결과 CONNECT 이후 추가되는 인증 snapshot을 하나의 map entry에 보관한다. */
    private static final class SessionState {

        private final WebSocketSession session;
        private volatile StompSessionAuthorization authorization;

        private SessionState(WebSocketSession session) {
            this.session = session;
        }

        private WebSocketSession session() {
            return session;
        }

        private StompSessionAuthorization authorization() {
            return authorization;
        }

        private void authenticate(StompSessionAuthorization authorization) {
            this.authorization = authorization;
        }
    }
}
