package com.opensource.docgrid.domain.failover.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/** Checks the database-arbitrated create, replay, and payload-conflict branches. */
@ExtendWith(MockitoExtension.class)
class HaProbeIdempotentWriteServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private Query insertQuery;
    @Mock private Query selectQuery;
    @InjectMocks private HaProbeIdempotentWriteService service;

    @AfterEach
    void clearDiagnosticContext() {
        HaProbeDiagnosticContext.clear();
    }

    @Test
    void firstWriteCreatesOneRow() throws Exception {
        stubInsert(1);

        assertThat(service.write("ha123run", "ha123run-v1-i1", "payload-1"))
            .isEqualTo(HaProbeIdempotentWriteService.Outcome.CREATED);
        assertThat(HaProbeDiagnosticContext.phase()).isEqualTo("COMMIT_PENDING");
        verify(insertQuery).executeUpdate();
        assertThat(HaProbeIdempotentWriteService.class
            .getMethod("write", String.class, String.class, String.class)
            .getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void samePayloadReplaysWithoutAnotherInsert() {
        stubInsert(0);
        stubStoredPayload("payload-1");

        assertThat(service.write("ha123run", "ha123run-v1-i1", "payload-1"))
            .isEqualTo(HaProbeIdempotentWriteService.Outcome.REPLAYED);
    }

    @Test
    void reusedIdWithDifferentPayloadConflicts() {
        stubInsert(0);
        stubStoredPayload("payload-1");

        assertThat(service.write("ha123run", "ha123run-v1-i1", "payload-2"))
            .isEqualTo(HaProbeIdempotentWriteService.Outcome.CONFLICT);
    }

    private void stubInsert(int inserted) {
        when(entityManager.createNativeQuery(anyString())).thenReturn(insertQuery, selectQuery);
        when(insertQuery.setParameter(anyString(), anyString())).thenReturn(insertQuery);
        when(insertQuery.executeUpdate()).thenReturn(inserted);
    }

    private void stubStoredPayload(String payload) {
        when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);
        when(selectQuery.getSingleResult()).thenReturn(payload);
    }
}
