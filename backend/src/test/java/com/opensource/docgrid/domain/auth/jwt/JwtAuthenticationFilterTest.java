package com.opensource.docgrid.domain.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 단위 테스트")
class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-authentication-filter-unit-test";

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private RoleAuthorityService roleAuthorityService;

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(TEST_SECRET, 3600L);
        filter = new JwtAuthenticationFilter(jwtProvider, tokenBlacklistService,
            roleAuthorityService, new ObjectMapper().findAndRegisterModules(),
            PathPatternRequestMatcher.withDefaults().matcher("/admin/**"));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효하고 블랙리스트에 없는 토큰이면 인증에 성공한다")
    void doFilter_authenticates_whenTokenValidAndNotBlacklisted() throws Exception {
        String token = jwtProvider.generateToken(1L, "user@test.com");
        given(tokenBlacklistService.isBlacklisted(anyString())).willReturn(false);
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("USER"));

        filter.doFilter(requestWithToken(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("블랙리스트에 등록된 토큰이면 인증하지 않는다")
    void doFilter_doesNotAuthenticate_whenTokenBlacklisted() throws Exception {
        String token = jwtProvider.generateToken(1L, "user@test.com");
        given(tokenBlacklistService.isBlacklisted(anyString())).willReturn(true);

        filter.doFilter(requestWithToken(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("블랙리스트 조회가 실패해도(Redis 장애) 인증은 계속 진행된다")
    void doFilter_authenticates_whenBlacklistCheckFails() throws Exception {
        String token = jwtProvider.generateToken(1L, "user@test.com");
        given(tokenBlacklistService.isBlacklisted(anyString())).willThrow(new RuntimeException("redis down"));
        given(roleAuthorityService.getRoles(1L)).willReturn(List.of("USER"));

        filter.doFilter(requestWithToken(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("관리자 요청은 Redis의 오래된 ADMIN 대신 primary의 현재 역할을 사용한다")
    void doFilter_adminRequestUsesCurrentPrimaryRoles() throws Exception {
        String token = jwtProvider.generateToken(1L, "user@test.com");
        given(tokenBlacklistService.isBlacklisted(anyString())).willReturn(false);
        given(roleAuthorityService.getRolesForAdmin(1L)).willReturn(List.of("USER"));

        filter.doFilter(adminRequestWithToken(token), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .extracting("authority").containsExactly("ROLE_USER");
        then(roleAuthorityService).should().getRolesForAdmin(1L);
        then(roleAuthorityService).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("primary 역할 검증 실패 시 관리자 요청을 503으로 거부한다")
    void doFilter_adminRequestFailsClosedWhenPrimaryUnavailable() throws Exception {
        String token = jwtProvider.generateToken(1L, "user@test.com");
        given(tokenBlacklistService.isBlacklisted(anyString())).willReturn(false);
        given(roleAuthorityService.getRolesForAdmin(1L))
            .willThrow(new IllegalStateException("primary unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(adminRequestWithToken(token), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("ROLE-005");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private MockHttpServletRequest adminRequestWithToken(String token) {
        MockHttpServletRequest request = requestWithToken(token);
        request.setRequestURI("/admin/workers");
        request.setServletPath("/admin/workers");
        return request;
    }
}
