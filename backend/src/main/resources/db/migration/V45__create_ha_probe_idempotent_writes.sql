-- Keep the retry experiment separate from V44's deliberately duplicate-observable probe.
-- The database, not either app instance, decides the winner of a concurrent request ID.
CREATE TABLE ha_probe_idempotent_writes (
    run_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    payload VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, request_id)
);
