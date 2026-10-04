package com.opensource.docgrid.global.diagnostics;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Correlates only the test-profile HA write request across authentication and database boundaries.
 *
 * <p>It records allowlisted IDs, phase and status on failures; it never logs JWTs, exception
 * messages or stack traces and does not retry or change the response.
 */
@Slf4j
@Component
@Profile("ha-probe")
@ConditionalOnProperty(name = "docgrid.ha-probe.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HaProbeDiagnosticFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HaProbeDiagnosticContext.isProbeRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HaProbeDiagnosticContext.begin(
            request.getHeader("X-Ha-Run-Id"), request.getHeader("X-Ha-Request-Id"));
        try {
            // 1. Wrap security as well as MVC so a failure before the controller is visible.
            filterChain.doFilter(request, response);
            if (response.getStatus() >= 500) {
                log.error("HA_PROBE_RESULT run={} request={} phase={} status={}",
                    HaProbeDiagnosticContext.runId(), HaProbeDiagnosticContext.requestId(),
                    HaProbeDiagnosticContext.phase(), response.getStatus());
            }
        } catch (ServletException | IOException | RuntimeException failure) {
            // 2. The exception may precede MVC advice; emit only its type and SQLSTATE.
            log.error("HA_PROBE_THROW run={} request={} phase={} type={} sqlstate={}",
                HaProbeDiagnosticContext.runId(), HaProbeDiagnosticContext.requestId(),
                HaProbeDiagnosticContext.phase(), failure.getClass().getSimpleName(),
                HaProbeDiagnosticContext.sqlState(failure));
            throw failure;
        } finally {
            // 3. A pooled servlet thread must not carry one request ID into another request.
            HaProbeDiagnosticContext.clear();
        }
    }
}
