package com.opensource.docgrid.domain.failover.service.command;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Commits a probe row through JPA/Hikari while marking execute and commit boundaries for test-only diagnostics. */
@Service
@Profile("ha-probe")
@RequiredArgsConstructor
public class HaProbeWriteService {

    private final EntityManager entityManager;

    @Transactional
    public void write(String runId, String requestId) {
        // 1. The transaction proxy has already opened the transaction before this method body.
        HaProbeDiagnosticContext.phase("SQL_EXECUTE");
        entityManager.createNativeQuery("INSERT INTO ha_probe_writes (run_id, request_id) VALUES (:runId, :requestId)")
            .setParameter("runId", runId)
            .setParameter("requestId", requestId)
            .executeUpdate();
        // 2. A failure after this point may be in commit; only the external DB ledger can settle its outcome.
        HaProbeDiagnosticContext.phase("COMMIT_PENDING");
        // 3. Do not deduplicate here: the external ledger must detect repeated physical writes.
    }
}
