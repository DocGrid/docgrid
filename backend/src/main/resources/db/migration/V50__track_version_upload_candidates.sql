-- 새 버전 원본의 저장 위치를 GCS 쓰기 전에 기록해 JVM 중단 후에도 같은 후보를 복구한다.
ALTER TABLE version_upload_requests
    ADD COLUMN candidate_storage_provider VARCHAR(20),
    ADD COLUMN candidate_bucket_name VARCHAR(255),
    ADD COLUMN candidate_object_key VARCHAR(1000),
    ADD COLUMN candidate_file_hash CHAR(64),
    ADD COLUMN candidate_file_size BIGINT,
    ADD COLUMN candidate_generation BIGINT,
    ADD COLUMN candidate_registered_at TIMESTAMPTZ,
    ADD COLUMN candidate_cleanup_token UUID,
    ADD COLUMN candidate_cleanup_lease_expires_at TIMESTAMPTZ;

ALTER TABLE version_upload_requests DROP CONSTRAINT ck_version_upload_requests_state;
ALTER TABLE version_upload_requests ADD CONSTRAINT ck_version_upload_requests_state
    CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'CLEANING'));

ALTER TABLE version_upload_requests DROP CONSTRAINT ck_version_upload_requests_result;
ALTER TABLE version_upload_requests ADD CONSTRAINT ck_version_upload_requests_result CHECK (
    state <> 'COMPLETED'
    OR (document_version_id IS NOT NULL AND version_no IS NOT NULL
        AND embedding_job_id IS NOT NULL AND document_status IS NOT NULL
        AND version_status IS NOT NULL AND job_status IS NOT NULL)
);

ALTER TABLE version_upload_requests ADD CONSTRAINT ck_version_upload_requests_candidate CHECK (
    (candidate_storage_provider IS NULL AND candidate_bucket_name IS NULL
        AND candidate_object_key IS NULL AND candidate_file_hash IS NULL
        AND candidate_file_size IS NULL AND candidate_generation IS NULL
        AND candidate_registered_at IS NULL)
    OR (candidate_storage_provider IS NOT NULL AND candidate_bucket_name IS NOT NULL
        AND candidate_object_key IS NOT NULL AND candidate_file_hash IS NOT NULL
        AND candidate_file_size IS NOT NULL AND candidate_registered_at IS NOT NULL)
);

ALTER TABLE version_upload_requests ADD CONSTRAINT ck_version_upload_candidate_cleanup CHECK (
    (candidate_cleanup_token IS NULL AND candidate_cleanup_lease_expires_at IS NULL)
    OR (candidate_cleanup_token IS NOT NULL AND candidate_cleanup_lease_expires_at IS NOT NULL)
);

CREATE INDEX idx_version_upload_candidates_reconcile
    ON version_upload_requests (state, lease_expires_at, candidate_registered_at)
    WHERE candidate_object_key IS NOT NULL;
