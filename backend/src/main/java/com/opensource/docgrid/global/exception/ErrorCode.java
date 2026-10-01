package com.opensource.docgrid.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전역 예외 응답에서 사용하는 HTTP 상태, 안정적인 오류 코드, 사용자 메시지의 중앙 정의 목록.
 *
 * <p>Controller와 Service는 개별 HTTP 응답을 직접 조립하지 않고 이 값을 담은 {@link DocGridException}을
 * 발생시켜 {@code GlobalExceptionHandler}가 일관된 오류 응답을 생성하도록 한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // COMMON
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON-001", "잘못된 요청입니다."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON-002", "요청 파라미터가 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-003", "리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-004", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-005", "지원하지 않는 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-006", "서버 내부 오류가 발생했습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-007", "인증이 필요합니다."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "COMMON-008", "데이터 충돌이 발생했습니다."),

    // USER
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER-002", "이미 사용 중인 이메일입니다."),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "USER-003", "비활성화된 계정입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "USER-004", "이메일 또는 비밀번호가 올바르지 않습니다."),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "USER-005", "비밀번호는 이메일 또는 이름과 같을 수 없습니다."),

    // DEPARTMENT
    DEPARTMENT_NOT_FOUND(HttpStatus.BAD_REQUEST, "DEPT-001", "존재하지 않는 부서입니다."),

    // ROLE
    ROLE_NOT_FOUND(HttpStatus.BAD_REQUEST, "ROLE-001", "존재하지 않는 역할입니다."),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "ROLE-002", "접근 권한이 없습니다."),
    ROLE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "ROLE-003", "이미 부여된 역할입니다."),
    ROLE_NOT_ASSIGNED(HttpStatus.NOT_FOUND, "ROLE-004", "부여되지 않은 역할입니다."),
    ADMIN_ROLE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ROLE-005", "관리자 권한을 확인할 수 없습니다."),

    // COLLECTION
    COLLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLECTION-001", "컬렉션을 찾을 수 없습니다."),
    COLLECTION_DOCUMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "COLLECTION-002", "이미 컬렉션에 추가된 문서입니다."),
    COLLECTION_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLECTION-003", "컬렉션에서 해당 문서를 찾을 수 없습니다."),
    COLLECTION_VISIBILITY_NOT_SUPPORTED(
        HttpStatus.BAD_REQUEST, "COLLECTION-004", "공개 범위는 PRIVATE 또는 PUBLIC만 지정할 수 있습니다."
    ),

    // DOCUMENT
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "DOCUMENT-001", "문서를 찾을 수 없습니다."),
    DOCUMENT_VERSION_SAME_CONTENT(
        HttpStatus.CONFLICT, "DOCUMENT-VERSION-001", "현재 버전과 동일한 파일입니다."
    ),
    DOCUMENT_VERSION_IN_PROGRESS(
        HttpStatus.CONFLICT, "DOCUMENT-VERSION-002", "처리 중인 문서 버전이 있습니다."
    ),
    DOCUMENT_VERSION_NOT_ALLOWED(
        HttpStatus.CONFLICT, "DOCUMENT-VERSION-003", "현재 문서 상태에서는 새 버전을 추가할 수 없습니다."
    ),
    DOCUMENT_VERSION_TYPE_MISMATCH(
        HttpStatus.BAD_REQUEST, "DOCUMENT-VERSION-004", "기존 문서와 다른 파일 형식은 업로드할 수 없습니다."
    ),
    DOCUMENT_VERSION_CHUNKING_NOT_ALLOWED(
        HttpStatus.CONFLICT, "DOCUMENT-VERSION-005", "현재 문서 버전 상태에서는 Chunk를 생성할 수 없습니다."
    ),
    DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED(
        HttpStatus.CONFLICT, "DOCUMENT-VERSION-006", "현재 문서 버전 상태에서는 Embedding을 생성할 수 없습니다."
    ),
    INDEXING_STATUS_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT-STATUS-001", "문서 인덱싱 상태를 조회할 수 없습니다."
    ),
    EMPTY_FILE(HttpStatus.BAD_REQUEST, "DOCUMENT-FILE-001", "빈 파일은 업로드할 수 없습니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "DOCUMENT-FILE-002", "파일 용량이 큽니다."),
    UNSUPPORTED_FILE_EXTENSION(HttpStatus.BAD_REQUEST, "DOCUMENT-FILE-003", "지원하지 않는 파일 확장자입니다."),
    UNSUPPORTED_FILE_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "DOCUMENT-FILE-004", "지원하지 않는 파일 형식입니다."),
    INVALID_FILE_NAME(HttpStatus.BAD_REQUEST, "DOCUMENT-FILE-005", "유효하지 않은 파일명입니다."),
    FILE_HASH_CALCULATION_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-FILE-006",
        "파일 해시를 계산하지 못했습니다."
    ),
    FILE_STORAGE_FAILED(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DOCUMENT-STORAGE-001",
        "파일 저장소를 사용할 수 없습니다."
    ),
    FILE_OBJECT_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "DOCUMENT-STORAGE-002",
        "저장된 파일을 찾을 수 없습니다."
    ),
    FILE_STORAGE_CONFIGURATION_MISMATCH(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-STORAGE-003",
        "파일 저장소 설정이 저장된 파일 위치와 일치하지 않습니다."
    ),
    FILE_OBJECT_RESOLUTION_FAILED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-UPLOAD-001",
        "파일 정보를 저장하지 못했습니다."
    ),
    UNSUPPORTED_DOCUMENT_TYPE(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-001",
        "지원하지 않는 문서 형식입니다."
    ),
    DOCUMENT_CONTENT_EMPTY(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-002",
        "문서에서 처리할 텍스트를 찾을 수 없습니다."
    ),
    DOCUMENT_CONTENT_NOT_AVAILABLE(
        HttpStatus.CONFLICT,
        "DOCUMENT-CONTENT-001",
        "현재 문서 버전의 추출 본문을 아직 조회할 수 없습니다."
    ),
    DOCUMENT_TEXT_DECODING_FAILED(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-003",
        "문서 텍스트를 UTF-8로 해석할 수 없습니다."
    ),
    DOCUMENT_FILE_REFERENCE_MISSING(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-PARSING-004",
        "문서 원본 파일 정보를 확인할 수 없습니다."
    ),
    DOCUMENT_PDF_ENCRYPTED(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-005",
        "암호화된 PDF 문서는 처리할 수 없습니다."
    ),
    DOCUMENT_OCR_REQUIRED(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-006",
        "PDF에서 텍스트를 찾을 수 없어 OCR 처리가 필요합니다."
    ),
    DOCUMENT_PARSING_FAILED(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-007",
        "문서 내용을 읽을 수 없습니다."
    ),
    DOCUMENT_CONTENT_GARBLED(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "DOCUMENT-PARSING-008",
        "문서 텍스트에 깨진 문자가 많아 처리할 수 없습니다."
    ),
    DOCUMENT_CHUNKS_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-CHUNK-001",
        "문서 버전과 Chunk 데이터가 일치하지 않습니다."
    ),
    DOCUMENT_EMBEDDINGS_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-EMBEDDING-001",
        "문서 버전과 Embedding 데이터가 일치하지 않습니다."
    ),
    EMBEDDING_VECTOR_INVALID(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-EMBEDDING-002",
        "생성된 Embedding Vector가 올바르지 않습니다."
    ),
    DOCUMENT_INDEXING_COMPLETION_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "DOCUMENT-INDEXING-001",
        "현재 상태에서는 문서 인덱싱을 완료할 수 없습니다."
    ),
    DOCUMENT_INDEXING_STALE_COMPLETION(
        HttpStatus.CONFLICT,
        "DOCUMENT-INDEXING-002",
        "최신 문서 버전이 아니므로 인덱싱을 완료할 수 없습니다."
    ),
    DOCUMENT_INDEXING_COMPLETION_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-INDEXING-003",
        "문서 인덱싱 완료 데이터를 확인할 수 없습니다."
    ),
    DOCUMENT_INDEXING_FAILURE_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "DOCUMENT-INDEXING-004",
        "문서 인덱싱 실패 데이터를 확인할 수 없습니다."
    ),
    DOCUMENT_VISIBILITY_NOT_SUPPORTED(
        HttpStatus.BAD_REQUEST,
        "DOCUMENT-VISIBILITY-001",
        "공개 범위는 PRIVATE 또는 PUBLIC만 지정할 수 있습니다."
    ),

    // PERMISSION
    INVALID_TARGET_TYPE(HttpStatus.BAD_REQUEST, "PERMISSION-001", "target_type과 ID 필드 조합이 올바르지 않습니다."),
    COLLECTION_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-002", "컬렉션 권한을 찾을 수 없습니다."),
    DOCUMENT_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "PERMISSION-003", "문서 권한을 찾을 수 없습니다."),
    ROLE_NOT_GRANTABLE(HttpStatus.BAD_REQUEST, "PERMISSION-004",
            "USER role은 모든 사용자가 보유하고 있어 권한 부여 대상으로 지정할 수 없습니다. 전체 공개가 목적이면 visibility를 PUBLIC으로 설정하세요."),
    COLLECTION_PERMISSION_ALREADY_GRANTED(HttpStatus.CONFLICT, "PERMISSION-005", "이미 이 대상에게 부여된 컬렉션 권한이 있습니다."),
    DOCUMENT_PERMISSION_ALREADY_GRANTED(HttpStatus.CONFLICT, "PERMISSION-006", "이미 이 대상에게 부여된 문서 권한이 있습니다."),

    // WORKER
    // Claim 요청의 Worker 식별자가 등록된 실행 인스턴스와 연결되지 않은 경우 사용한다.
    WORKER_NOT_FOUND(HttpStatus.NOT_FOUND, "WORKER-001", "Worker를 찾을 수 없습니다."),
    // 저장 상태 또는 Heartbeat 기준 실질 상태가 Job을 받을 수 없는 경우 사용한다.
    WORKER_NOT_AVAILABLE(HttpStatus.CONFLICT, "WORKER-002", "Worker가 Job을 처리할 수 없는 상태입니다."),

    // EMBEDDING JOB
    EMBEDDING_JOB_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "EMBEDDING-JOB-001",
        "Embedding Job을 찾을 수 없습니다."
    ),
    EMBEDDING_JOB_NOT_PROCESSING(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-002",
        "현재 상태에서는 Embedding Job Attempt를 시작할 수 없습니다."
    ),
    EMBEDDING_JOB_OWNERSHIP_INVALID(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-003",
        "현재 Embedding Job 소유권과 요청이 일치하지 않습니다."
    ),
    EMBEDDING_JOB_LEASE_EXPIRED(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-004",
        "Embedding Job Lease가 만료되었습니다."
    ),
    EMBEDDING_JOB_OWNERSHIP_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EMBEDDING-JOB-005",
        "Embedding Job 소유권 데이터를 확인할 수 없습니다."
    ),
    EMBEDDING_JOB_ATTEMPT_INVALID(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-006",
        "현재 Claim 실행 Context와 Attempt가 일치하지 않습니다."
    ),
    EMBEDDING_JOB_FAILURE_CONFLICT(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-007",
        "동일한 Embedding Job Attempt에 다른 실패 내용이 이미 기록되었습니다."
    ),
    // 처리 중이거나 자동 재시도가 예정된 Job과 중복 수동 재처리 요청을 함께 거부할 때 사용한다.
    EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-008",
        "최종 실패한 Embedding Job만 수동으로 재처리할 수 있습니다."
    ),
    // Job 상태는 최종 실패지만 대상 문서나 Version이 재처리 조건을 만족하지 않는 경우 사용한다.
    EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID(
        HttpStatus.CONFLICT,
        "EMBEDDING-JOB-009",
        "현재 문서 상태에서는 Embedding Job을 수동으로 재처리할 수 없습니다."
    ),

    // EMBEDDING MODEL
    EMBEDDING_MODEL_NOT_CONFIGURED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EMBEDDING-MODEL-001",
        "사용 가능한 임베딩 모델이 설정되지 않았습니다."
    ),
    MULTIPLE_ACTIVE_EMBEDDING_MODELS(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "EMBEDDING-MODEL-002",
        "사용 가능한 임베딩 모델이 여러 개 설정되어 있습니다."
    ),

    // SYNC
    SYNC_EVENT_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "SYNC-001",
        "동기화 Event를 찾을 수 없습니다."
    ),
    SYNC_EVENT_OWNERSHIP_INVALID(
        HttpStatus.CONFLICT,
        "SYNC-002",
        "현재 동기화 Event 소유권과 요청이 일치하지 않습니다."
    ),
    SYNC_EVENT_INCONSISTENT(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "SYNC-003",
        "동기화 Event와 도메인 상태가 일치하지 않습니다."
    ),
    SYNC_EVENT_RETRY_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "SYNC-004",
        "최종 실패한 동기화 Event만 재처리할 수 있습니다."
    ),
    SYNC_ISSUE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "SYNC-005",
        "동기화 정합성 Issue를 찾을 수 없습니다."
    ),
    SYNC_ISSUE_REPAIR_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "SYNC-006",
        "현재 Issue는 안전한 자동 복구를 요청할 수 없습니다."
    ),
    SYNC_ISSUE_IGNORE_NOT_ALLOWED(
        HttpStatus.CONFLICT,
        "SYNC-007",
        "OPEN 상태의 Issue만 무시할 수 있습니다."
    ),

    // SEARCH
    EMBEDDING_SERVER_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SEARCH-001",
        "임베딩 서버를 사용할 수 없습니다."
    ),
    EMBEDDING_DIMENSION_MISMATCH(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "SEARCH-002",
        "임베딩 차원이 설정된 모델과 일치하지 않습니다."
    ),
    EMBEDDING_PROVIDER_OVERLOADED(
        HttpStatus.TOO_MANY_REQUESTS,
        "SEARCH-003",
        "임베딩 서버가 처리 가능한 요청 수를 초과했습니다."
    ),
    EMBEDDING_PROVIDER_TIMEOUT(
        HttpStatus.GATEWAY_TIMEOUT,
        "SEARCH-004",
        "임베딩 서버 응답 제한 시간을 초과했습니다."
    ),
    EMBEDDING_PROVIDER_CIRCUIT_OPEN(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SEARCH-005",
        "임베딩 서버 장애 보호가 활성화되었습니다."
    ),
    EMBEDDING_REQUEST_REJECTED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "SEARCH-006",
        "임베딩 서버가 요청 계약을 거부했습니다."
    ),
    SEARCH_CONVERSATION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "SEARCH-007",
        "대화 기록을 찾을 수 없습니다."
    ),

    // RAG
    RAG_SERVICE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RAG-001",
        "LLM 서버를 사용할 수 없습니다."
    ),
    RAG_ANSWER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "RAG-002",
        "검색 요청을 찾을 수 없습니다."
    ),

    // MCP
    RATE_LIMIT_EXCEEDED(
        HttpStatus.TOO_MANY_REQUESTS,
        "MCP-001",
        "호출 횟수 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
