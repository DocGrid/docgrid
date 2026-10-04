package com.opensource.docgrid.global.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Checks that probe correlation keeps only safe identifiers, phases and SQLSTATE codes. */
class HaProbeDiagnosticContextTest {

    @AfterEach
    void clearThreadContext() {
        HaProbeDiagnosticContext.clear();
    }

    @Test
    void extractsSqlStateWithoutKeepingTheExceptionMessage() {
        var failure = new DataAccessResourceFailureException(
            "private host and connection URL", new SQLException("private host", "08006"));

        assertThat(HaProbeDiagnosticContext.sqlState(failure)).isEqualTo("08006");
        assertThat(HaProbeDiagnosticContext.sqlState(
            new JDBCConnectionException("private JDBC text", new SQLException("private host", "25006"))))
            .isEqualTo("25006");
        assertThat(HaProbeDiagnosticContext.sqlState(new IllegalStateException("private host")))
            .isEqualTo("none");
    }

    @Test
    void malformedHeadersBecomeOmissionMarkers() {
        HaProbeDiagnosticContext.begin("ha418test1", "unsafe value with spaces and host");

        assertThat(HaProbeDiagnosticContext.runId()).isEqualTo("ha418test1");
        assertThat(HaProbeDiagnosticContext.requestId()).isEqualTo("omitted");
        assertThat(HaProbeDiagnosticContext.phase()).isEqualTo("AUTHORIZATION");

        HaProbeDiagnosticContext.begin("internal-host", "ha418test1-v1-i1");
        assertThat(HaProbeDiagnosticContext.runId()).isEqualTo("omitted");
        assertThat(HaProbeDiagnosticContext.requestId()).isEqualTo("omitted");
    }
}
