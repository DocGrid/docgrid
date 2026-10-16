-- 동일 회수의 결과 불명을 새 WAL 재주장으로 해소한다. 삭제된 권한 ID에는 FK를 걸지 않는다.
CREATE TABLE permission_revocation_requests (
    request_id UUID PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL,
    assertion_count BIGINT NOT NULL DEFAULT 0,
    last_asserted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_permission_revocation_requests_document ON permission_revocation_requests(document_id);
