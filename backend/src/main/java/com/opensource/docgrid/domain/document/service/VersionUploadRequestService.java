package com.opensource.docgrid.domain.document.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.storage.StoredFile;
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
 * 저장소 후보 위치는 외부 쓰기 전에 기록해 중단 후 같은 키의 재시도와 안전한 정리를 지원한다.
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
            return new Claim(userId, documentId, key, token, null, null);
        }

        // 3. 완료된 키라도 입력이 다르면 원래 업로드 결과를 공개하지 않는다.
        RequestRow row = jdbcTemplate.queryForObject("""
            SELECT input_fingerprint, state, document_version_id, version_no, embedding_job_id,
                   current_version_id, document_status, version_status, job_status,
                   candidate_storage_provider, candidate_bucket_name, candidate_object_key,
                   candidate_file_hash, candidate_file_size, candidate_generation
              FROM version_upload_requests
             WHERE user_id = ? AND document_id = ? AND request_key = ?
            """, (rs, ignored) -> new RequestRow(
            rs.getString("input_fingerprint"), rs.getString("state"),
            rs.getObject("document_version_id", Long.class), rs.getObject("version_no", Integer.class),
            rs.getObject("embedding_job_id", Long.class), rs.getObject("current_version_id", Long.class),
            rs.getString("document_status"), rs.getString("version_status"), rs.getString("job_status"),
            rs.getString("candidate_object_key") == null ? null : new Candidate(
                new StoredFile(StorageProvider.valueOf(rs.getString("candidate_storage_provider")),
                    rs.getString("candidate_bucket_name"), rs.getString("candidate_object_key")),
                rs.getString("candidate_file_hash"), rs.getLong("candidate_file_size"),
                rs.getObject("candidate_generation", Long.class)
            )
        ), userId, documentId, key);
        if (!fingerprint.equals(row.fingerprint())) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_KEY_CONFLICT);
        }
        if ("COMPLETED".equals(row.state())) {
            return new Claim(userId, documentId, key, null, new DocumentVersionUploadResponse(
                documentId, row.versionId(), row.versionNo(), row.jobId(), row.currentVersionId(),
                DocumentStatus.valueOf(row.documentStatus()), DocumentVersionStatus.valueOf(row.versionStatus()),
                EmbeddingJobStatus.valueOf(row.jobStatus())
            ), null);
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
            return new Claim(userId, documentId, key, token, null, row.candidate());
        }
        throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_IN_PROGRESS);
    }

    /** 새 버전·Outbox·Job과 같은 트랜잭션에서 최초 응답을 확정한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, DocumentVersionUploadResponse response, boolean candidateClaimed) {
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
        if (candidateClaimed) {
            // DB가 채택한 원본은 정리 후보에서 제외한다. 버전 생성과 함께 커밋되어야 한다.
            jdbcTemplate.update("""
                UPDATE version_upload_requests
                   SET candidate_storage_provider = NULL, candidate_bucket_name = NULL,
                       candidate_object_key = NULL, candidate_file_hash = NULL,
                       candidate_file_size = NULL, candidate_generation = NULL,
                       candidate_registered_at = NULL
                 WHERE user_id = ? AND document_id = ? AND request_key = ? AND owner_token = ?
                """, claim.userId(), claim.documentId(), claim.key(), claim.token());
        }
    }

    /** 외부 저장 전에 현재 처리자의 후보 위치와 예상 바이너리를 별도 트랜잭션으로 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerCandidate(Claim claim, StoredFile candidate, String fileHash, long fileSize) {
        int updated = jdbcTemplate.update("""
            UPDATE version_upload_requests
               SET candidate_storage_provider = ?, candidate_bucket_name = ?, candidate_object_key = ?,
                   candidate_file_hash = ?, candidate_file_size = ?, candidate_generation = NULL,
                   candidate_registered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND owner_token = ?
               AND state = 'IN_PROGRESS' AND candidate_cleanup_token IS NULL
               AND candidate_object_key IS NULL
            """, candidate.storageProvider().name(), candidate.bucketName(), candidate.objectKey(),
            fileHash, fileSize, claim.userId(), claim.documentId(), claim.key(), claim.token());
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
    }

    /** 저장 후 확인한 generation을 같은 후보와 현재 처리자에게만 기록한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCandidateGeneration(Claim claim, StoredFile candidate, long generation) {
        int updated = jdbcTemplate.update("""
            UPDATE version_upload_requests SET candidate_generation = ?, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND owner_token = ?
               AND state = 'IN_PROGRESS' AND candidate_object_key = ?
            """, generation, claim.userId(), claim.documentId(), claim.key(), claim.token(),
            candidate.objectKey());
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

    /** 유예 기간이 지난 미확정 후보를 조회한다. 조회만으로 처리권이나 저장소 상태는 바꾸지 않는다. */
    public List<CleanupTarget> findCleanupTargets(Duration gracePeriod, int limit) {
        if (gracePeriod.isNegative() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid cleanup scan options");
        }
        return jdbcTemplate.query("""
            SELECT user_id, document_id, request_key, candidate_storage_provider, candidate_bucket_name,
                   candidate_object_key, candidate_file_hash, candidate_file_size, candidate_generation
              FROM version_upload_requests
             WHERE candidate_object_key IS NOT NULL
               AND candidate_registered_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
               AND (state = 'COMPLETED' OR (state IN ('IN_PROGRESS', 'CLEANING')
                    AND lease_expires_at <= CURRENT_TIMESTAMP))
               AND (candidate_cleanup_lease_expires_at IS NULL
                    OR candidate_cleanup_lease_expires_at <= CURRENT_TIMESTAMP)
             ORDER BY candidate_registered_at, user_id, document_id, request_key LIMIT ?
            """, (rs, ignored) -> new CleanupTarget(
            rs.getLong("user_id"), rs.getLong("document_id"), rs.getString("request_key"),
            new Candidate(new StoredFile(
                StorageProvider.valueOf(rs.getString("candidate_storage_provider")),
                rs.getString("candidate_bucket_name"), rs.getString("candidate_object_key")
            ), rs.getString("candidate_file_hash"), rs.getLong("candidate_file_size"),
                rs.getObject("candidate_generation", Long.class))
        ), gracePeriod.toSeconds(), limit);
    }

    /** 이전 업로더를 먼저 무효화해야 외부 후보를 삭제할 수 있다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CleanupClaim reserveCleanup(CleanupTarget target, Duration gracePeriod) {
        UUID cleanupToken = UUID.randomUUID();
        int updated = jdbcTemplate.update("""
            UPDATE version_upload_requests
               SET state = CASE WHEN state = 'IN_PROGRESS' THEN 'CLEANING' ELSE state END,
                   owner_token = ?, candidate_cleanup_token = ?,
                   candidate_cleanup_lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND document_id = ? AND request_key = ? AND candidate_object_key = ?
               AND candidate_registered_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
               AND (state = 'COMPLETED' OR (state IN ('IN_PROGRESS', 'CLEANING')
                    AND lease_expires_at <= CURRENT_TIMESTAMP))
               AND (candidate_cleanup_lease_expires_at IS NULL
                    OR candidate_cleanup_lease_expires_at <= CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), cleanupToken, target.userId(), target.documentId(), target.key(),
            target.candidate().storedFile().objectKey(), gracePeriod.toSeconds());
        return updated == 1 ? new CleanupClaim(target, cleanupToken) : null;
    }

    /** 삭제 직전에 동일 위치를 참조하는 FileObject가 존재하는지 확인한다. */
    public boolean isReferenced(CleanupClaim claim) {
        StoredFile file = claim.target().candidate().storedFile();
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM file_objects
             WHERE storage_provider = ? AND bucket_name = ? AND object_key = ?
            """, Integer.class, file.storageProvider().name(), file.bucketName(), file.objectKey());
        return count != null && count > 0;
    }

    /** 후보 처리 결과를 기록하며 실패나 객체 부재 시 다음 회차를 위해 위치를 보존한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCleanup(CleanupClaim claim, boolean removeCandidate) {
        int updated;
        if (removeCandidate) {
            updated = jdbcTemplate.update("""
                UPDATE version_upload_requests
                   SET state = CASE WHEN state = 'CLEANING' THEN 'IN_PROGRESS' ELSE state END,
                       lease_expires_at = CURRENT_TIMESTAMP,
                       candidate_storage_provider = NULL, candidate_bucket_name = NULL,
                       candidate_object_key = NULL, candidate_file_hash = NULL,
                       candidate_file_size = NULL, candidate_generation = NULL,
                       candidate_registered_at = NULL, candidate_cleanup_token = NULL,
                       candidate_cleanup_lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ? AND document_id = ? AND request_key = ? AND candidate_cleanup_token = ?
                """, claim.target().userId(), claim.target().documentId(), claim.target().key(),
                claim.cleanupToken());
        } else {
            updated = jdbcTemplate.update("""
                UPDATE version_upload_requests
                   SET state = CASE WHEN state = 'CLEANING' THEN 'IN_PROGRESS' ELSE state END,
                       lease_expires_at = CURRENT_TIMESTAMP,
                       candidate_cleanup_token = NULL, candidate_cleanup_lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ? AND document_id = ? AND request_key = ? AND candidate_cleanup_token = ?
                """, claim.target().userId(), claim.target().documentId(), claim.target().key(),
                claim.cleanupToken());
        }
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
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

    /** 현재 처리 소유권 또는 변경되지 않는 새 버전 업로드 응답이다. */
    public record Claim(
        Long userId, Long documentId, String key, UUID token,
        DocumentVersionUploadResponse completedResponse, Candidate candidate
    ) {
    }

    /** 중단된 시도의 저장 위치와 입력에서 계산한 바이너리 지문이다. */
    public record Candidate(StoredFile storedFile, String fileHash, long fileSize, Long generation) {
    }

    /** 정리 가능한 후보의 내부 식별자이며 요청 키는 로그에 기록하지 않는다. */
    public record CleanupTarget(Long userId, Long documentId, String key, Candidate candidate) {
    }

    /** 후보 정리를 예약한 작업만 결과를 확정할 수 있게 하는 소유권 토큰이다. */
    public record CleanupClaim(CleanupTarget target, UUID cleanupToken) {
    }

    /** DB 원장에서 읽은 완료 응답과 입력 지문에 한정된 내부 조회 결과다. */
    private record RequestRow(
        String fingerprint, String state, Long versionId, Integer versionNo, Long jobId,
        Long currentVersionId, String documentStatus, String versionStatus, String jobStatus,
        Candidate candidate
    ) {
    }
}
