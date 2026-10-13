-- 최초 문서 업로드의 처리 소유권과 최초 201 응답을 사용자·요청 키별로 보존한다.
CREATE TABLE initial_upload_requests (
    user_id BIGINT NOT NULL REFERENCES users (id),
    request_key VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    owner_token UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    document_id BIGINT,
    document_version_id BIGINT,
    file_object_id BIGINT,
    embedding_job_id BIGINT,
    document_status VARCHAR(30),
    job_status VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_initial_upload_requests PRIMARY KEY (user_id, request_key),
    CONSTRAINT ck_initial_upload_requests_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_initial_upload_requests_result CHECK (
        state = 'IN_PROGRESS'
        OR (document_id IS NOT NULL AND document_version_id IS NOT NULL
            AND file_object_id IS NOT NULL AND embedding_job_id IS NOT NULL
            AND document_status IS NOT NULL AND job_status IS NOT NULL)
    )
);
