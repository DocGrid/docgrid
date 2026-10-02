-- The HTTP HA probe is profile-gated; this inert table records only test request IDs.
-- No uniqueness constraint is used: reconciliation must be able to observe duplicate commits.
CREATE TABLE ha_probe_writes (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ha_probe_writes_run_request ON ha_probe_writes (run_id, request_id);
