package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

import java.security.Principal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import com.opensource.docgrid.domain.auth.websocket.StompSessionAuthorization;
import com.opensource.docgrid.domain.auth.websocket.StompSessionRegistry;

import io.jsonwebtoken.Claims;

/**
 * STOMP CONNECT·STOMP 명령의 JWT, blacklist, 역할 조회와 세션 수명 snapshot 등록 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StompAuthChannelInterceptor 단위 테스트")
class StompAuthChannelInterceptorTest {

    @Mock private JwtProvider jwtProvider;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private RoleAuthorityService roleAuthorityService;
    @Mock private StompSessionRegistry stompSessionRegistry;
    @Mock private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(
            jwtProvider,
            tokenBlacklistService,
            roleAuthorityService,
            stompSessionRegistry
        );
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("정상 케이스: 유효한 ADMIN 토큰이면 연결 프레임에 Principal을 부착한다")
    void preSend_attachesPrincipal_whenTokenValid(StompCommand command) {
        // Given
        Claims claims = mock(Claims.class);
        given(claims.getSubject()).willReturn("admin@example.com");
        given(claims.get("userId", Long.class)).willReturn(1L);
        given(claims.get("jti", String.class)).willReturn("valid-jti");
        given(claims.getExpiration()).willReturn(Date.from(Instant.parse("2026-09-28T01:00:00Z")));
        given(jwtProvider.getClaimsIfValid("valid-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("valid-jti")).willReturn(false);
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("ADMIN"));
        given(stompSessionRegistry.authenticate(eq("stomp-session"), any(StompSessionAuthorization.class)))
            .willReturn(true);

        Message<byte[]> connectMessage = connectMessage(command, "Bearer valid-token");

        // When
        Message<?> result = interceptor.preSend(connectMessage, channel);

        // Then
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        Principal user = resultAccessor.getUser();
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("admin@example.com");
        assertThat(((Authentication) user).getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
        then(tokenBlacklistService).should().isBlacklisted("valid-jti");
        then(stompSessionRegistry).should()
            .authenticate(eq("stomp-session"), any(StompSessionAuthorization.class));
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("예외 케이스: Authorization 헤더가 없으면 연결을 거부한다")
    void preSend_throws_whenNoAuthorizationHeader(StompCommand command) {
        // Given
        Message<byte[]> connectMessage = connectMessage(command, null);

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(jwtProvider).shouldHaveNoInteractions();
        then(tokenBlacklistService).shouldHaveNoInteractions();
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 토큰이 유효하지 않으면 연결을 거부한다")
    void preSend_throws_whenTokenInvalid() {
        // Given
        given(jwtProvider.getClaimsIfValid("invalid-token")).willReturn(null);
        Message<byte[]> connectMessage = connectMessage("Bearer invalid-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(tokenBlacklistService).shouldHaveNoInteractions();
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: jti가 없는 토큰이면 연결을 거부한다")
    void preSend_throws_whenJtiMissing() {
        // Given
        Claims claims = mock(Claims.class);
        given(jwtProvider.getClaimsIfValid("missing-jti-token")).willReturn(claims);
        Message<byte[]> connectMessage = connectMessage("Bearer missing-jti-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(tokenBlacklistService).shouldHaveNoInteractions();
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 로그아웃으로 블랙리스트에 등록된 토큰이면 연결을 거부한다")
    void preSend_throws_whenTokenBlacklisted() {
        // Given
        Claims claims = mock(Claims.class);
        given(claims.get("jti", String.class)).willReturn("blacklisted-jti");
        given(jwtProvider.getClaimsIfValid("blacklisted-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("blacklisted-jti")).willReturn(true);
        Message<byte[]> connectMessage = connectMessage("Bearer blacklisted-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: Redis에서 폐기 여부를 확인할 수 없으면 연결을 거부한다")
    void preSend_throws_whenBlacklistCheckFails() {
        // Given
        Claims claims = mock(Claims.class);
        given(claims.get("jti", String.class)).willReturn("unverifiable-jti");
        given(jwtProvider.getClaimsIfValid("unverifiable-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("unverifiable-jti"))
            .willThrow(new RuntimeException("redis down"));
        Message<byte[]> connectMessage = connectMessage("Bearer unverifiable-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 만료 시각이 없는 token은 세션 수명을 추적할 수 없어 거부한다")
    void preSend_throws_whenExpirationMissing() {
        // Given
        Claims claims = mock(Claims.class);
        given(claims.getSubject()).willReturn("missing-expiration@example.com");
        given(claims.get("userId", Long.class)).willReturn(1L);
        given(claims.get("jti", String.class)).willReturn("missing-expiration-jti");
        given(jwtProvider.getClaimsIfValid("missing-expiration-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("missing-expiration-jti")).willReturn(false);
        Message<byte[]> connectMessage = connectMessage("Bearer missing-expiration-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
        then(roleAuthorityService).shouldHaveNoInteractions();
        then(stompSessionRegistry).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 물리 연결을 추적할 수 없으면 인증된 세션으로 등록하지 않는다")
    void preSend_throws_whenTransportSessionMissing() {
        // Given
        Claims claims = mock(Claims.class);
        given(claims.getSubject()).willReturn("missing-transport@example.com");
        given(claims.get("userId", Long.class)).willReturn(1L);
        given(claims.get("jti", String.class)).willReturn("missing-transport-jti");
        given(claims.getExpiration()).willReturn(Date.from(Instant.parse("2026-09-28T01:00:00Z")));
        given(jwtProvider.getClaimsIfValid("missing-transport-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("missing-transport-jti")).willReturn(false);
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("USER"));
        given(stompSessionRegistry.authenticate(eq("stomp-session"), any(StompSessionAuthorization.class)))
            .willReturn(false);
        Message<byte[]> connectMessage = connectMessage("Bearer missing-transport-token");

        // When & Then
        assertThatThrownBy(() -> interceptor.preSend(connectMessage, channel))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("CONNECT가 아닌 프레임은 검증 없이 통과시킨다")
    void preSend_skipsValidation_forNonConnectFrames() {
        // Given
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        Message<byte[]> sendMessage = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // When
        Message<?> result = interceptor.preSend(sendMessage, channel);

        // Then
        assertThat(result).isSameAs(sendMessage);
        then(jwtProvider).shouldHaveNoInteractions();
        then(tokenBlacklistService).shouldHaveNoInteractions();
        then(roleAuthorityService).shouldHaveNoInteractions();
    }

    private Message<byte[]> connectMessage(String authorizationHeader) {
        return connectMessage(StompCommand.CONNECT, authorizationHeader);
    }

    private Message<byte[]> connectMessage(StompCommand command, String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("stomp-session");
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
