package com.opensource.docgrid.domain.failover.service.command;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContext;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Applies a synthetic HA write at most once per run and request ID in the surviving database.
 *
 * <p>The database uniqueness constraint arbitrates concurrent app instances. This service never
 * retries an uncertain transaction or claims to protect other product writes from asynchronous RPO.
 */
@Service
@Profile("ha-probe")
@RequiredArgsConstructor
public class HaProbeIdempotentWriteService {

    private final EntityManager entityManager;

    /** Describes the committed effect that the HTTP controller may report after this method returns. */
    public enum Outcome { CREATED, REPLAYED, CONFLICT }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome write(String runId, String requestId, String payload) {
        // 1. The unique key chooses one writer even when app A and B receive the same ID together.
        HaProbeDiagnosticContext.phase("SQL_EXECUTE");
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO ha_probe_idempotent_writes (run_id, request_id, payload)
                VALUES (:runId, :requestId, :payload)
                ON CONFLICT (run_id, request_id) DO NOTHING
                """)
            .setParameter("runId", runId)
            .setParameter("requestId", requestId)
            .setParameter("payload", payload)
            .executeUpdate();
        if (inserted == 1) {
            HaProbeDiagnosticContext.phase("COMMIT_PENDING");
            return Outcome.CREATED;
        }
        // 2. A separate READ COMMITTED statement sees a concurrent winner after conflict waiting.
        String storedPayload = (String) entityManager.createNativeQuery("""
                SELECT payload FROM ha_probe_idempotent_writes
                WHERE run_id = :runId AND request_id = :requestId
                """)
            .setParameter("runId", runId)
            .setParameter("requestId", requestId)
            .getSingleResult();
        HaProbeDiagnosticContext.phase("COMMIT_PENDING");
        // 3. A reused ID with different content is never reported as a successful replay.
        return storedPayload.equals(payload) ? Outcome.REPLAYED : Outcome.CONFLICT;
    }
}
