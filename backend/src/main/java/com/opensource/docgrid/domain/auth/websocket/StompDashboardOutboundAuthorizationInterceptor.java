package com.opensource.docgrid.domain.auth.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 기존 대시보드 구독에 대한 실제 outbound MESSAGE 전송을 push별 primary 판정으로 제한한다.
 *
 * <p>SUBSCRIBE 이후 역할이 회수되어도 브로커에는 구독이 남을 수 있다. 이 경계는 각 물리 세션에
 * 전달할 때 실제 물리 세션과 서버 내부의 불변 판정 결과를 대조한다. 판정 결과가 없거나 ADMIN이
 * 아니면 메시지를 버린다. 판정 수집 뒤 연결된 세션은 닫지 않고, 후보였지만 ADMIN이 회수된
 * 세션만 닫는다.
 * RAG 개인 알림과 연결 제어 프레임은 이 검사 대상이 아니다.
 */
@Component
@RequiredArgsConstructor
public class StompDashboardOutboundAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";

    private final StompSessionRegistry stompSessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // 1. 브로커가 구독자에게 보내는 대시보드 MESSAGE만 검사한다.
        if (!SimpMessageType.MESSAGE.equals(SimpMessageHeaderAccessor.getMessageType(message.getHeaders()))
            || !DASHBOARD_TOPIC.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
            return message;
        }

        // 2. 실제 수신 세션의 인증 snapshot이 없거나 CONNECT 당시 ADMIN이 아니면 버린다.
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        StompSessionAuthorization authorization = stompSessionRegistry.authorizationFor(sessionId);
        if (authorization == null || !authorization.roles().contains("ADMIN")) {
            if (sessionId != null) {
                stompSessionRegistry.close(sessionId);
            }
            return null;
        }

        // 3. 이 push의 primary 판정이 없으면 이전 CONNECT 역할로 폴백하지 않고 차단한다.
        Object decision = message.getHeaders().get(DashboardAuthorizationSnapshot.HEADER);
        if (!(decision instanceof DashboardAuthorizationSnapshot snapshot)) {
            return null;
        }

        // 4. 늦게 연결된 세션은 이번 push만 건너뛰고, 확인된 비ADMIN 후보는 연결도 닫는다.
        if (snapshot.allows(sessionId, authorization.userId())) {
            return message;
        }
        if (snapshot.wasCandidate(sessionId, authorization.userId())) {
            stompSessionRegistry.close(sessionId);
        }
        return null;
    }
}
