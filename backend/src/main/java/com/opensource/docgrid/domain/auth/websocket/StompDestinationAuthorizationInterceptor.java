package com.opensource.docgrid.domain.auth.websocket;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * {@code /topic/dashboard} 목적지를 ADMIN 전용으로 보호한다.
 *
 * <p>{@code StompAuthChannelInterceptor}가 CONNECT 시점에 세션에 부착한 Principal을 재사용해
 * 목적지 접근 시점에 다시 한 번 검증한다. CONNECT 검증 하나에만 의존하지 않는 이중 방어다.
 *
 * <p>SUBSCRIBE뿐 아니라 SEND도 차단한다. {@code enableSimpleBroker("/topic")} 구성에서는
 * 클라이언트가 {@code /topic/dashboard}로 STOMP SEND 프레임을 보내면 SimpleBroker가 이를 그대로
 * 구독자 전원에게 브로드캐스트한다 — 인증만 된 일반 사용자도 위조된 지표를 ADMIN 구독자에게 보낼
 * 수 있다는 뜻이다. 실제 push는 {@code DashboardWebSocketController}가 {@code clientInboundChannel}을
 * 거치지 않는 {@code SimpMessagingTemplate}으로만 하므로, 이 목적지로의 클라이언트발 SEND는
 * ADMIN 여부와 무관하게 전부 차단해도 정상 기능에 영향이 없다.
 *
 * <p>{@code @EnableWebSocketSecurity}(Spring Security 메시지 인가 DSL)는 STOMP endpoint가
 * 등록된 것을 감지하면 세션 기반 CSRF 토큰을 무조건 요구하는 {@code CsrfChannelInterceptor}를
 * 함께 붙인다. 이 앱은 세션이 없는 stateless JWT 인증이라 CSRF 토큰이 존재할 수 없어 모든 CONNECT가
 * {@code MissingCsrfTokenException}으로 거부되므로, 그 DSL 대신 이 수동 Interceptor로 구현한다.
 */
@Component
public class StompDestinationAuthorizationInterceptor implements ChannelInterceptor {

    private static final String DASHBOARD_TOPIC = "/topic/dashboard";
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        /*
         * /topic/dashboard 목적지가 아니면 이 Interceptor는 그냥 통과시킨다 — 다른 목적지별로
         * 전담 Interceptor가 따로 있는 게 아니라, 그 목적지들은 애초에 이 검사 대상이 아니다.
         * 예를 들어 RAG 개인 알림(/user/queue/rag-answer)은 Spring의 user-destination 격리
         * 자체가 안전을 보장해서 별도 Interceptor가 필요 없다.
         */
        if (accessor == null || !DASHBOARD_TOPIC.equals(accessor.getDestination())) {
            return message;
        }

        /*
         * SEND는 ADMIN 여부와 무관하게 전부 차단한다 — SimpleBroker 구성상 클라이언트가 SEND
         * 프레임을 보내면 그대로 구독자 전원에게 방송돼버려, 위조된 값을 퍼뜨릴 수 있는 경로이기
         * 때문이다. 실제 push는 서버 쪽 SimpMessagingTemplate로만 이뤄지므로 클라이언트발 SEND는
         * 정상 기능이 아니다.
         */
        if (StompCommand.SEND.equals(accessor.getCommand())) {
            throw new AccessDeniedException("이 목적지로는 메시지를 보낼 수 없습니다.");
        }

        /* SUBSCRIBE 시점에 세션에 부착된 Principal이 ADMIN 권한인지 검증한다. */
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) && !isAdmin(accessor.getUser())) {
            throw new AccessDeniedException("대시보드 구독 권한이 없습니다.");
        }

        return message;
    }

    private boolean isAdmin(Principal user) {
        if (!(user instanceof Authentication authentication)) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
