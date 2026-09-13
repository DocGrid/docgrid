package com.opensource.docgrid.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtAuthenticationFilter;
import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.mcp.security.McpApiKeyAuthFilter;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.global.exception.RestAccessDeniedHandler;
import com.opensource.docgrid.global.exception.RestAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 API의 CORS, JWT, MCP API Key 인증과 역할 기반 인가를 구성한다.
 *
 * <p>Actuator 요청은 우선순위가 더 높은 {@link ManagementEndpointSecurityConfig}가 담당하며, 이
 * 설정은 나머지 애플리케이션 요청의 보안 경계를 책임진다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtProvider jwtProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final RoleAuthorityService roleAuthorityService;
    private final McpAccessTokenCommandService mcpAccessTokenCommandService;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    /**
     * MCP({@code /mcp})와 웹 API({@code /mcp/tokens} 포함)를 포함한 전체 보안 필터 체인을 구성한다.
     *
     * <p>인증(신원 확인)은 {@link JwtAuthenticationFilter}(웹 로그인, {@code /mcp/tokens} 등)와
     * {@link McpApiKeyAuthFilter}(Claude Desktop API 키, {@code /mcp} 전용)로 나뉘며, 각자
     * {@code shouldNotFilter()}로 자기 담당 경로만 처리한다. 인가(권한 판정)는
     * {@code authorizeHttpRequests}가 별도로 담당한다.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/test/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/departments").permitAll()
                .requestMatchers("/auth/signup", "/auth/login").permitAll()
                // WebSocket 인증·인가는 3단계로 나뉜다. 네이티브 websocket Transport가 Upgrade
                // 요청에 커스텀 헤더를 못 실어서, 여기(HTTP)에서는 검증하지 않는다:
                // 1. HTTP 핸드셰이크(여기) — permitAll
                // 2. STOMP CONNECT — StompAuthChannelInterceptor가 JWT 검증
                // 3. STOMP SUBSCRIBE·SEND — DashboardSubscriptionAuthorizationInterceptor가 목적지별 권한 검증
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // 인증 실패와 권한 부족을 상태 코드로 구분하고, 본문 없는 기본 응답 대신 공통 ErrorResponse를 준다.
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler)
            )
            // UsernamePasswordAuthenticationFilter는 폼 로그인용이라 실제로는 안 쓰지만, addFilterBefore(A, B.class)가
            // "A를 B보다 앞자리에 꽂아라"는 뜻이라 위치 기준점(앵커)으로만 재사용한다 — 이 필터 앞에 꽂아야
            // 두 인증 필터가 authorizeHttpRequests의 최종 인가 판정보다 먼저 실행돼 SecurityContext를 채울 수 있다.
            .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, tokenBlacklistService, roleAuthorityService), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new McpApiKeyAuthFilter(mcpAccessTokenCommandService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
