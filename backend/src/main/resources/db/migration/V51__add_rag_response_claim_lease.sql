ALTER TABLE rag_responses
    ADD COLUMN claim_owner VARCHAR(80),
    ADD COLUMN claim_token VARCHAR(80),
    ADD COLUMN claim_lease_until TIMESTAMP;

CREATE INDEX idx_rag_responses_claim_lease
    ON rag_responses (claim_lease_until)
    WHERE status = 'PROCESSING';
