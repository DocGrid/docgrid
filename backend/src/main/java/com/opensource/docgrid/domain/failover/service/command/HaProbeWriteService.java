package com.opensource.docgrid.domain.failover.service.command;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Commits a probe row through JPA and the normal application Hikari connection path. */
@Service
@Profile("ha-probe")
@RequiredArgsConstructor
public class HaProbeWriteService {

    private final EntityManager entityManager;

    @Transactional
    public void write(String runId, String requestId) {
        // 1. Keep the database commit in the request thread so HTTP success means commit completed.
        entityManager.createNativeQuery("INSERT INTO ha_probe_writes (run_id, request_id) VALUES (:runId, :requestId)")
            .setParameter("runId", runId)
            .setParameter("requestId", requestId)
            .executeUpdate();
        // 2. Do not deduplicate here: the external ledger must detect repeated physical writes.
    }
}
