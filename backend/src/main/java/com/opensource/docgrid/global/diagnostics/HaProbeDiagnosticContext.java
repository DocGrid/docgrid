package com.opensource.docgrid.global.diagnostics;

import java.sql.SQLException;
import java.util.regex.Pattern;

import org.hibernate.JDBCException;
import org.slf4j.MDC;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Keeps only allowlisted HA probe identifiers and the last database phase in the request thread.
 *
 * <p>The test-only filter clears this context after each request; exception messages, hostnames and
 * connection URLs never enter it. It does not change transaction or retry behavior.
 */
public final class HaProbeDiagnosticContext {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("ha[0-9]{3}[A-Za-z0-9._-]{0,55}");
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("ha[0-9]{3}[A-Za-z0-9._-]{0,55}-v[0-9]+-i[0-9]+");
    private static final Pattern SAFE_SQL_STATE = Pattern.compile("[A-Z0-9]{5}");
    private static final String RUN_KEY = "haProbeRunId";
    private static final String REQUEST_KEY = "haProbeRequestId";
    private static final String PHASE_KEY = "haProbePhase";

    private HaProbeDiagnosticContext() {
    }

    public static boolean isProbeRequest(HttpServletRequest request) {
        return "/api/ha-probe/writes".equals(request.getRequestURI())
            || "/api/ha-probe/idempotent-writes".equals(request.getRequestURI());
    }

    public static void begin(String runId, String requestId) {
        // 1. Never log arbitrary client headers; malformed values become a fixed omission marker.
        String safeRunId = safeRunId(runId);
        MDC.put(RUN_KEY, safeRunId);
        MDC.put(REQUEST_KEY, safeRequestId(safeRunId, requestId));
        MDC.put(PHASE_KEY, "AUTHORIZATION");
    }

    public static void phase(String phase) {
        MDC.put(PHASE_KEY, phase);
    }

    public static String runId() {
        return value(RUN_KEY);
    }

    public static String requestId() {
        return value(REQUEST_KEY);
    }

    public static String phase() {
        return value(PHASE_KEY);
    }

    public static String sqlState(Throwable failure) {
        // 2. Inspect bounded cause links only; never copy SQL exception messages to a test log.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            String state = cause instanceof SQLException sqlException ? sqlException.getSQLState()
                : cause instanceof JDBCException jdbcException ? jdbcException.getSQLState() : null;
            if (state != null && SAFE_SQL_STATE.matcher(state).matches()) {
                return state;
            }
        }
        return "none";
    }

    public static void clear() {
        // 3. Servlet threads are reused, so no request may inherit the previous request's IDs.
        MDC.remove(RUN_KEY);
        MDC.remove(REQUEST_KEY);
        MDC.remove(PHASE_KEY);
    }

    private static String safeRunId(String candidate) {
        return candidate != null && SAFE_RUN_ID.matcher(candidate).matches() ? candidate : "omitted";
    }

    private static String safeRequestId(String runId, String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
            && candidate.startsWith(runId + "-v") ? candidate : "omitted";
    }

    private static String value(String key) {
        String value = MDC.get(key);
        return value == null ? "omitted" : value;
    }
}
