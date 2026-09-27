package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

import java.security.Principal;
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

import io.jsonwebtoken.Claims;

@ExtendWith(MockitoExtension.class)
@DisplayName("StompAuthChannelInterceptor 단위 테스트")
class StompAuthChannelInterceptorTest {

    @Mock private JwtProvider jwtProvider;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private RoleAuthorityService roleAuthorityService;
    @Mock private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtProvider, tokenBlacklistService, roleAuthorityService);
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
        given(jwtProvider.getClaimsIfValid("valid-token")).willReturn(claims);
        given(tokenBlacklistService.isBlacklisted("valid-jti")).willReturn(false);
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("ADMIN"));

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
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
