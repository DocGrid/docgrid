package com.opensource.docgrid.domain.auth.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기존 대시보드 구독에 대한 실제 outbound MESSAGE 전송을 현재 primary 역할로 제한한다.
 *
 * <p>SUBSCRIBE 이후 역할이 회수되어도 브로커에는 구독이 남을 수 있다. 이 경계는 각 물리 세션에
 * 전달할 때 다시 확인하며, 역할 조회가 불가능하거나 ADMIN이 아니면 메시지를 버리고 세션을 닫는다.
 * RAG 개인 알림과 연결 제어 프레임은 이 검사 대상이 아니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompDashboardOutboundAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";

    private final StompSessionRegistry stompSessionRegistry;
    private final RoleAuthorityService roleAuthorityService;

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

        // 3. 현재 primary의 역할을 확인한다. 조회 실패도 이전 ADMIN snapshot으로 우회하지 않는다.
        try {
            if (roleAuthorityService.getRolesForAdmin(authorization.userId()).contains("ADMIN")) {
                return message;
            }
        } catch (RuntimeException exception) {
            log.warn("STOMP 대시보드 전송의 최신 역할을 확인할 수 없어 차단합니다: {}", exception.getMessage());
        }

        // 4. 회수되었거나 확인할 수 없는 세션은 메시지를 버리고 다음 전송도 받지 않도록 닫는다.
        stompSessionRegistry.close(sessionId);
        return null;
    }
}
