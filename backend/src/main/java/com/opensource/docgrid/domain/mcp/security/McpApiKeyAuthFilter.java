package com.opensource.docgrid.domain.mcp.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * {@code /mcp}로 오는 MCP 프로토콜 요청을 API 키(장기 MCP 토큰)로 인증하는 필터.
 *
 * <p>웹 로그인은 JWT를 쓰지만 JWT는 만료 시간이 짧아(1시간) Claude Desktop처럼 설정 파일에
 * 한 번 등록해두고 계속 재사용하는 시나리오엔 맞지 않는다. 그래서 별도의 장기 API 키 방식을
 * 도입했고, 이 필터가 그 검증을 담당한다. {@link #shouldNotFilter}로 {@code /mcp} 경로에만
 * 좁게 적용되며, {@code /mcp/tokens} 등 다른 경로는
 * {@link com.opensource.docgrid.domain.auth.jwt.JwtAuthenticationFilter}가 별도로 담당한다.
 */
@RequiredArgsConstructor
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    // WebMvcConfig의 OSIV 제외 경로와 동일한 값을 참조해야 하므로 public으로 공개한다.
    public static final String MCP_ENDPOINT = "/mcp";

    private final McpAccessTokenCommandService mcpAccessTokenCommandService;

    /**
     * MCP Streamable HTTP는 응답을 비동기 재디스패치로 처리한다. {@link SecurityContextHolder}에만
     * 세팅하면 그 스레드가 끝나는 순간 인증 정보가 사라져서, 재디스패치 시점에
     * {@code SecurityContextHolderFilter}가 빈 컨텍스트를 다시 로드해
     * {@code AuthorizationDeniedException}이 발생한다. 요청(request) attribute에 명시적으로
     * 저장해 재디스패치에서도 같은 인증 정보를 복원할 수 있게 한다.
     */
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MCP_ENDPOINT.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request); // "Authorization: Bearer {토큰}" 헤더에서 토큰 값만 추출
        if (StringUtils.hasText(token)) {
            Optional<Long> userId = mcpAccessTokenCommandService.authenticate(token);
            userId.ifPresent(id -> {
                /*
                 * principal에는 JWT 필터처럼 userId를 바로 넣지 않고 고정 문자열("mcp-client")만
                 * 넣는다 — API 키엔 email 같은 신원 표시값이 없어서다. 진짜 userId는 details에
                 * 저장하므로, 도구 핸들러에서 사용자를 식별할 땐 getPrincipal()이 아니라
                 * getDetails()를 써야 한다.
                 */
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken("mcp-client", null, List.of());
                authentication.setDetails(id);

                SecurityContext context = SecurityContextHolder.getContext();
                context.setAuthentication(authentication);
                securityContextRepository.saveContext(context, request, response);
            });
        }

        /*
         * 인증 실패(SecurityContext가 비어있음)의 최종 차단은 이 필터가 아니라 SecurityConfig의
         * anyRequest().authenticated() + RestAuthenticationEntryPoint가 401로 응답한다.
         */
        filterChain.doFilter(request, response);
    }

    // "Authorization: Bearer {토큰}" 형식의 헤더에서 {토큰} 부분만 잘라내는 헬퍼
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            // "Bearer "는 정확히 7글자 → 그 뒤부터가 실제 토큰 값
            return bearer.substring(7);
        }
        return null;
    }
}