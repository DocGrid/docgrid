package com.opensource.docgrid.domain.document.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 새 버전 업로드 키의 처리 소유권과 최초 응답을 사용자·대상 문서별로 보존한다.
 *
 * <p>파일 저장 중에는 DB 잠금을 유지하지 않는다. 완료 결과는 버전·Job 생성 트랜잭션에
 * 기록하며, 만료된 소유자를 교체한 경우 이전 소유자의 커밋은 토큰 비교로 거부한다.
 * 저장소 후보 객체의 중단 후 복구는 이 요청 원장의 책임 범위가 아니다.
 */
@Service
@RequiredArgsConstructor
public class VersionUploadRequestService {

    private static final String KEY_PATTERN = "[A-Za-z0-9._~-]{1,128}";

    private final JdbcTemplate jdbcTemplate;
    private final PermissionQueryService permissionQueryService;

    /** 파일 해시와 파일 메타데이터를 길이 구분한 SHA-256 지문으로 묶는다. */
    public String fingerprint(ValidatedFile file, String fileHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, fileHash);
            add(digest, file.originalFilename());
            add(digest, file.contentType());
            add(digest, Long.toString(file.fileSize()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, exception);
        }
    }

    /** 현재 WRITE 권한을 확인한 뒤 새 처리권, 완료 응답 또는 진행 중·입력 충돌을 판정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim acquire(Long userId, Long documentId, String key, String fingerprint) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_KEY_INVALID);
        }
        // 1. 완료 응답 재사용도 현재 문서 WRITE 권한이 있을 때만 허용한다.
        if (!permissionQueryService.canWriteDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 사용자·문서·키의 유일 제약으로 앱 인스턴스 사이에서 첫 처리자를 정한다.
        UUID token = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
            INSERT INTO version_upload_requests
                (user_id, document_id, request_key, input_fingerprint, state, owner_token, lease_expires_at)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
            ON CONFLICT (user_id, document_id, request_key) DO NOTHING
            """, userId, documentId, key, fingerprint, token);
        if (inserted == 1) {
            return new Claim(userId, documentId, key, token, null);
        }

        // 3. 완료된 키라도 입력이 다르면 원래 업로드 결과를 공개하지 않는다.
        RequestRow row = jdbcTemplate.queryForObject("""
            SELECT input_fingerprint, state, document_version_id, version_no, embedding_job_id,
                   current_version_id, document_status, version_status, job_status
              FROM version_upload_requests
             WHERE user_id = ? AND document_id = ? AND request_key = ?
            """, (rs, ignored) -> new RequestRow(
            rs.getString("input_fingerprint"), rs.getString("state"),
            rs.getObject("document_version_id", Long.class), rs.getObject("version_no", Integer.class),
            rs.getObject("embedding_job_id", Long.class), rs.getObject("current_version_id", Long.class),
            rs.getString("document_status"), rs.getString("version_status"), rs.getString("job_status")
        ), userId, documentId, key);
        if (!fingerprint.equals(row.fingerprint())) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_KEY_CONFLICT);
        }
        if ("COMPLETED".equals(row.state())) {
            return new Claim(userId, documentId, key, null, new DocumentVersionUploadResponse(
                documentId, row.versionId(), row.versionNo(), row.jobId(), row.currentVersionId(),
                DocumentStatus.valueOf(row.documentStatus()), DocumentVersionStatus.valueOf(row.versionStatus()),
                EmbeddingJobStatus.valueOf(row.jobStatus())
            ));
        }

        // 4. 소유권 만료 뒤에는 한 요청만 처리권을 다시 획득한다.
        int reclaimed = jdbcTemplate.update("""
            UPDATE version_upload_requests
               SET owner_token = ?, lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND state = 'IN_PROGRESS'
               AND lease_expires_at <= CURRENT_TIMESTAMP
            """, token, userId, documentId, key);
        if (reclaimed == 1) {
            return new Claim(userId, documentId, key, token, null);
        }
        throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_IN_PROGRESS);
    }

    /** 새 버전·Outbox·Job과 같은 트랜잭션에서 최초 응답을 확정한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, DocumentVersionUploadResponse response) {
        int updated = jdbcTemplate.update("""
            UPDATE version_upload_requests
               SET state = 'COMPLETED', document_version_id = ?, version_no = ?, embedding_job_id = ?,
                   current_version_id = ?, document_status = ?, version_status = ?, job_status = ?,
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND owner_token = ?
               AND state = 'IN_PROGRESS'
            """, response.documentVersionId(), response.versionNo(), response.embeddingJobId(),
            response.currentVersionId(), response.documentStatus().name(), response.versionStatus().name(),
            response.jobStatus().name(), claim.userId(), claim.documentId(), claim.key(), claim.token());
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
    }

    /** 확인된 실패의 처리권을 즉시 만료시켜 같은 입력으로 다시 시도하게 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Claim claim) {
        jdbcTemplate.update("""
            UPDATE version_upload_requests
               SET lease_expires_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND owner_token = ?
               AND state = 'IN_PROGRESS'
            """, claim.userId(), claim.documentId(), claim.key(), claim.token());
    }

    private void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** 현재 처리 소유권 또는 변경되지 않는 최초 업로드 응답이다. */
    public record Claim(
        Long userId, Long documentId, String key, UUID token, DocumentVersionUploadResponse completedResponse
    ) {
    }

    /** DB 원장에서 읽은 완료 응답과 입력 지문에 한정된 내부 조회 결과다. */
    private record RequestRow(
        String fingerprint, String state, Long versionId, Integer versionNo, Long jobId,
        Long currentVersionId, String documentStatus, String versionStatus, String jobStatus
    ) {
    }
}
