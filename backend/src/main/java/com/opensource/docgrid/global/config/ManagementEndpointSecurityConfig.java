package com.opensource.docgrid.global.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator 엔드포인트에만 적용되는 보안 경계를 구성한다.
 *
 * <p>상태 확인과 Prometheus 수집은 별도 Management 포트에서 인증 없이 허용한다. 향후 다른
 * Actuator 엔드포인트가 실수로 노출 목록에 추가되더라도 이 체인이 기본 거부해 운영 정보가 공개되지
 * 않도록 하는 것이 이 설정의 책임이다. 애플리케이션 API의 JWT·MCP 인증은 {@link SecurityConfig}의
 * 필터 체인이 담당한다.
 */
@Configuration(proxyBeanMethods = false)
public class ManagementEndpointSecurityConfig {

    /**
     * 노출을 승인한 health와 prometheus만 공개하고 나머지 Actuator 요청은 거부한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementEndpointSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // EndpointRequest를 사용해 Management base path가 바뀌어도 같은 보안 경계를 유지한다.
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                // 설정 실수로 다른 Actuator 엔드포인트가 노출돼도 인증 없는 접근을 허용하지 않는다.
                .anyRequest().denyAll()
            );
        return http.build();
    }
}
