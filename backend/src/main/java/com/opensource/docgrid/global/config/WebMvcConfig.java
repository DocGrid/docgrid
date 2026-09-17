package com.opensource.docgrid.global.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.opensource.docgrid.domain.auth.resolver.CurrentUserArgumentResolver;
import com.opensource.docgrid.domain.mcp.security.McpApiKeyAuthFilter;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;

/**
 * Spring MVC 확장 설정.
 *
 * <p>{@link CurrentUserArgumentResolver}를 등록해 컨트롤러가 {@code @CurrentUser}로 인증된
 * 사용자 ID를 받을 수 있게 하고, {@code /mcp}를 제외한 나머지 경로에 OSIV(Open Session In
 * View) 인터셉터를 등록한다. {@code /mcp}는 MCP Streamable HTTP 응답이 비동기 재디스패치로
 * 처리돼 OSIV가 요청 종료 신호를 못 받아 DB 커넥션이 반납되지 않는 문제(#120)가 있어,
 * {@code spring.jpa.open-in-view=false}로 전역 OSIV를 끈 뒤 이 클래스가 {@code /mcp}만
 * 뺀 나머지 경로에 직접 재등록한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * {@code /mcp}를 제외한 모든 경로에 OSIV 인터셉터를 등록한다.
     *
     * <p>{@link EntityManagerFactory}를 생성자 필드 대신 {@link ObjectProvider}로 받는 이유:
     * {@code @WebMvcTest} 슬라이스는 JPA 계층을 안 올려 이 빈이 없는데도, 이 클래스가
     * {@link WebMvcConfigurer} 구현체라는 이유만으로 스캔 대상이 되어 컨텍스트 로딩이
     * 실패한다. 빈이 없으면 인터셉터 등록 자체를 건너뛰어 이 문제를 피한다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
        if (entityManagerFactory == null) {
            return;
        }
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagerFactory);
        /*
         * addWebRequestInterceptor()로 이 OSIV 인터셉터를 모든 경로에 등록하되,
         * excludePathPatterns()로 /mcp 하나만 등록 대상에서 뺀다 — application.yml의
         * spring.jpa.open-in-view=false로 전역으로 꺼둔 OSIV를, /mcp를 제외한 나머지
         * 경로에서만 이 줄이 다시 켜주는 셈이다(클래스 Javadoc 참고, #120).
         *
         * "/mcp"라는 경로 문자열을 여기 직접 적지 않고 McpApiKeyAuthFilter.MCP_ENDPOINT
         * 상수를 그대로 참조하는 이유: 그 필터도 동일한 "/mcp" 문자열로 자기 담당 경로를
         * 판단하는데, 두 파일에 각각 "/mcp"를 따로 적어두면 나중에 경로가 바뀔 때 한쪽만
         * 고치는 실수로 두 곳이 어긋날 수 있다. 상수 하나를 공유해 그 위험을 없앤다.
         */
        registry.addWebRequestInterceptor(interceptor).excludePathPatterns(McpApiKeyAuthFilter.MCP_ENDPOINT);
    }
}
