package com.opensource.docgrid.domain.auth.jwt;

import java.util.List;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

/**
 * STOMP 연결 프레임의 JWT를 검증해 WebSocket 세션에 Principal을 부착한다.
 *
 * <p>WebSocket 자체는 그냥 양방향 파이프를 열어줄 뿐, "이 메시지가 연결 요청인지 구독인지" 같은
 * 구조가 없다. STOMP는 그 파이프 위에 CONNECT·SUBSCRIBE·SEND 같은 프레임 타입을 정의해 의미
 * 있는 대화를 가능하게 하는 프로토콜이고, 클라이언트는 파이프가 열리면 규격상 반드시 CONNECT 또는
 * STOMP 프레임을 제일 먼저 보내야 한다. 이 Interceptor는 그 연결 프레임을 가로채 "로그인한
 * 사용자인가"를 확인하는 문지기이며, JWT가 없거나 무효하면 그 자리에서 연결을 끊는다.
 *
 * <p>HTTP 핸드셰이크(/ws)는 {@code SecurityConfig}에서 permitAll로 열려 있다. 네이티브
 * {@code websocket} Transport는 Upgrade 요청에 커스텀 헤더를 실을 수 없어 HTTP 레벨 인증이
 * Transport 종류에 따라 되다 안되다 하므로, 인증은 Transport와 무관하게 항상 커스텀 헤더를 실을
 * 수 있는 STOMP CONNECT 프레임으로 옮긴다. SUBSCRIBE 권한 검증은 {@link
 * com.opensource.docgrid.domain.dashboard.websocket.DashboardSubscriptionAuthorizationInterceptor}가
 * 별도로 담당한다.
 *
 * <p>토큰 파싱과 {@code JwtProvider} 검증은 {@code JwtAuthenticationFilter}의 HTTP 경로와
 * 같은 규칙을 그대로 재사용한다. 다만 결과를 담는 곳이 다르다 — HTTP는 요청 하나로 끝나 매번
 * {@code SecurityContextHolder}를 새로 채우지만, WebSocket은 연결이 오래 유지되는 세션이라
 * {@code accessor.setUser()}로 세션 자체에 Principal을 붙여 이후 모든 프레임에서 재사용한다.
 *
 * <p>이때 Accessor는 반드시 {@link MessageHeaderAccessor#getAccessor}로 가져와야 한다.
 * {@code StompHeaderAccessor.wrap(message)}는 검증 전용 복사본이라 그 위에 {@code setUser()}를
 * 호출해도 반환되는 {@code message}에는 반영되지 않고, SUBSCRIBE 단계에서 Principal이 조용히
 * 사라지는 문제로 이어진다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final RoleAuthorityService roleAuthorityService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // CONNECT와 STOMP는 모두 같은 연결 메시지다. SUBSCRIBE 등 이후 프레임은 그대로 통과시킨다.
        if (accessor != null && SimpMessageType.CONNECT.equals(accessor.getMessageType())) {
            String token = resolveToken(accessor);
            // JWT가 유효해야 신원을 확인한 것으로 본다. 없거나 무효하면 여기서 바로 연결을 끊는다.
            Claims claims = token == null ? null : jwtProvider.getClaimsIfValid(token);
            if (claims == null) {
                throw new AccessDeniedException("유효하지 않은 인증 정보입니다.");
            }

            String email = claims.getSubject();
            Long userId = claims.get("userId", Long.class);
            List<String> roles = roleAuthorityService.getRoles(userId);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(email, null, authorities);
            authentication.setDetails(userId);
            accessor.setUser(authentication);
        }

        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
