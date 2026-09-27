package com.opensource.docgrid.domain.auth.websocket;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * STOMP client의 SUBSCRIBE·SEND 목적지를 명시적인 허용 목록으로 제한한다.
 *
 * <p>{@code StompAuthChannelInterceptor}가 CONNECT 시점에 세션에 부착한 Principal을 재사용해
 * 목적지 접근 시점에 다시 검증한다. {@code /topic/dashboard}는 ADMIN만, 사용자별 RAG 완료 알림인
 * {@code /user/queue/rag-answer}는 인증된 사용자만 구독할 수 있다. 그 외 정확한 목적지와 pattern,
 * Spring이 내부에서 만드는 실제 {@code /queue} 목적지는 모두 거부한다.
 *
 * <p>애플리케이션에는 client가 호출할 {@code @MessageMapping}이 없고 실제 push는 서버의
 * {@code SimpMessagingTemplate}만 사용한다. 서버 전송은 {@code clientInboundChannel}을 거치지 않으므로
 * client의 SEND와 서버 전용 MESSAGE 명령을 목적지와 무관하게 거부해도 정상 push에는 영향이 없다.
 *
 * <p>{@code @EnableWebSocketSecurity}(Spring Security 메시지 인가 DSL)는 STOMP endpoint가
 * 등록된 것을 감지하면 세션 기반 CSRF 토큰을 무조건 요구하는 {@code CsrfChannelInterceptor}를
 * 함께 붙인다. 이 앱은 세션이 없는 stateless JWT 인증이라 CSRF 토큰이 존재할 수 없어 모든 CONNECT가
 * {@code MissingCsrfTokenException}으로 거부되므로, 그 DSL 대신 이 수동 Interceptor로 구현한다.
 */
@Component
public class StompDestinationAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final String RAG_ANSWER_QUEUE = "/user/queue/rag-answer";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final String SUBSCRIPTION_DENIED_MESSAGE = "구독 권한이 없습니다.";
    private static final String SEND_DENIED_MESSAGE = "메시지를 보낼 권한이 없습니다.";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        // 1. SEND와 서버 전용 MESSAGE는 같은 발행 타입이므로 command 별칭과 무관하게 모두 거부한다.
        if (SimpMessageType.MESSAGE.equals(accessor.getMessageType())) {
            throw new AccessDeniedException(SEND_DENIED_MESSAGE);
        }

        // 2. CONNECT·DISCONNECT·UNSUBSCRIBE 등 목적지 인가 대상이 아닌 명령은 그대로 통과시킨다.
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        // 3. 넓은 pattern 대신 프런트가 실제 사용하는 두 목적지만 정확히 일치할 때 허용한다.
        String destination = accessor.getDestination();
        if (DASHBOARD_TOPIC.equals(destination) && isAdmin(accessor.getUser())) {
            return message;
        }
        if (RAG_ANSWER_QUEUE.equals(destination) && isAuthenticated(accessor.getUser())) {
            return message;
        }

        // 4. pattern·내부 queue·알 수 없는 목적지와 권한 부족을 같은 응답으로 거부한다.
        throw new AccessDeniedException(SUBSCRIPTION_DENIED_MESSAGE);
    }

    private boolean isAdmin(Principal user) {
        if (!(user instanceof Authentication authentication)) {
            return false;
        }
        return authentication.isAuthenticated() && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ADMIN_AUTHORITY::equals);
    }

    private boolean isAuthenticated(Principal user) {
        return user instanceof Authentication authentication && authentication.isAuthenticated();
    }
}
