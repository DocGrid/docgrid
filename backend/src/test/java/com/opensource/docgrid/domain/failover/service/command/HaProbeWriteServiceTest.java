package com.opensource.docgrid.domain.failover.service.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/** Verifies that the external request ID is persisted through the JPA write boundary. */
@ExtendWith(MockitoExtension.class)
class HaProbeWriteServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;
    @InjectMocks private HaProbeWriteService service;

    @AfterEach
    void clearDiagnosticContext() {
        HaProbeDiagnosticContext.clear();
    }

    @Test
    void writesRunAndRequestIdInOneTransaction() throws Exception {
        when(entityManager.createNativeQuery(
            "INSERT INTO ha_probe_writes (run_id, request_id) VALUES (:runId, :requestId)"))
            .thenReturn(query);
        when(query.setParameter("runId", "run-1")).thenReturn(query);
        when(query.setParameter("requestId", "run-1-v1-i1")).thenReturn(query);

        when(query.executeUpdate()).thenAnswer(invocation -> {
            assertThat(HaProbeDiagnosticContext.phase()).isEqualTo("SQL_EXECUTE");
            return 1;
        });

        service.write("run-1", "run-1-v1-i1");

        verify(query).executeUpdate();
        assertThat(HaProbeDiagnosticContext.phase()).isEqualTo("COMMIT_PENDING");
        assertNotNull(HaProbeWriteService.class.getMethod("write", String.class, String.class)
            .getAnnotation(Transactional.class));
    }
}
