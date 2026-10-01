package com.opensource.docgrid.domain.auth.jwt;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.global.common.response.ErrorResponse;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP JWT를 인증하고 요청별 현재 권한을 SecurityContext에 넣는다.
 *
 * <p>관리자 요청은 Redis 권한 캐시를 신뢰하지 않고 primary를 직접 확인한다.
 * 확인할 수 없으면 이전 ADMIN 권한으로 진행시키지 않고 503을 반환한다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final RoleAuthorityService roleAuthorityService;
    private final ObjectMapper objectMapper;
    private final RequestMatcher adminRequests;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = JwtProvider.resolveToken(request);

        if (StringUtils.hasText(token)) {
            Claims claims = jwtProvider.getClaimsIfValid(token);
            if (claims != null && !isBlacklisted(claims.get("jti", String.class))) {
                Long userId = claims.get("userId", Long.class);
                String email = claims.getSubject();
                // 1. 관리자 경로는 매번 primary에서 검증하고 일반 경로만 Redis 역할 캐시를 사용한다.
                boolean adminPath = adminRequests.matches(request);
                List<String> roles;
                try {
                    roles = adminPath ? roleAuthorityService.getRolesForAdmin(userId)
                        : roleAuthorityService.getRoles(userId);
                } catch (RuntimeException e) {
                    if (!adminPath) {
                        throw e;
                    }
                    // 2. primary 확인이 불가능하면 캐시된 ADMIN으로 통과시키지 않는다.
                    log.error("관리자 권한 primary 검증 실패: {}", e.getClass().getSimpleName());
                    ErrorCode errorCode = ErrorCode.ADMIN_ROLE_UNAVAILABLE;
                    response.setStatus(errorCode.getHttpStatus().value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode, request));
                    return;
                }

                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(email, null, authorities);
                authentication.setDetails(userId);

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isBlacklisted(String jti) {
        try {
            return tokenBlacklistService.isBlacklisted(jti);
        } catch (Exception e) {
            log.error("Redis 블랙리스트 조회 실패, 인증을 계속 진행합니다: {}", e.getMessage());
            return false;
        }
    }
}
