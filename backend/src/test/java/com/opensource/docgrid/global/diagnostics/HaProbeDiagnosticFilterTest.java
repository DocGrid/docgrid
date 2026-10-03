package com.opensource.docgrid.global.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;

import jakarta.servlet.http.HttpServletResponse;

/** Verifies that the test-only filter correlates failures without leaking headers or thread state. */
@ExtendWith(OutputCaptureExtension.class)
class HaProbeDiagnosticFilterTest {

    private final HaProbeDiagnosticFilter filter = new HaProbeDiagnosticFilter();

    @Test
    void correlatesHttp500AndClearsTheServletThread(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ha-probe/writes");
        request.addHeader("X-Ha-Run-Id", "ha418test1");
        request.addHeader("X-Ha-Request-Id", "ha418test1-v1-i1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(HaProbeDiagnosticContext.requestId()).isEqualTo("ha418test1-v1-i1");
            HaProbeDiagnosticContext.phase("TX_BEGIN");
            ((HttpServletResponse) res).setStatus(500);
        });

        assertThat(output.getAll()).contains("HA_PROBE_RESULT run=ha418test1 request=ha418test1-v1-i1");
        assertThat(output.getAll()).contains("phase=TX_BEGIN status=500");
        assertThat(MDC.get("haProbeRequestId")).isNull();
    }

    @Test
    void skipsUnrelatedRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/documents");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            assertThat(HaProbeDiagnosticContext.requestId()).isEqualTo("omitted"));
    }

    @Test
    void classifiesFailureBeforeControllerWithoutLoggingTheCause(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ha-probe/writes");
        request.addHeader("X-Ha-Run-Id", "ha418test1");
        request.addHeader("X-Ha-Request-Id", "ha418test1-v1-i1");
        var failure = new DataAccessResourceFailureException(
            "private host", new SQLException("private connection URL", "08006"));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
            (req, res) -> { throw failure; }))
            .isSameAs(failure);

        assertThat(output.getAll()).contains(
            "HA_PROBE_THROW run=ha418test1 request=ha418test1-v1-i1 phase=AUTHORIZATION");
        assertThat(output.getAll()).contains("type=DataAccessResourceFailureException sqlstate=08006");
        assertThat(output.getAll()).doesNotContain("private host", "private connection URL");
        assertThat(MDC.get("haProbeRequestId")).isNull();
    }
}
