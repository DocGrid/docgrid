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

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 최초 업로드 키의 소유권을 짧은 별도 트랜잭션에서 정하고 완료 응답을 업로드 트랜잭션에 기록한다.
 *
 * <p>외부 파일 저장 중에는 DB 잠금을 잡지 않는다. 만료된 소유권은 원자적으로 교체할 수 있으며,
 * 기존 소유자는 완료 시 토큰 비교에 실패해 문서 생성 트랜잭션 전체가 롤백된다.
 * 후보의 예상 위치와 해시는 외부 저장소 호출 전에 확정하고, 만료된 소유자의 후보만 복구 대상으로 전달한다.
 */
@Service
@RequiredArgsConstructor
public class InitialUploadRequestService {

    private static final String KEY_PATTERN = "[A-Za-z0-9._~-]{1,128}";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 파일과 메타데이터를 길이 구분한 SHA-256 지문으로 묶어 같은 키의 입력 변경을 판별한다.
     */
    public String fingerprint(DocumentUploadRequest request, ValidatedFile file, String fileHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, fileHash);
            add(digest, file.originalFilename());
            add(digest, file.contentType());
            add(digest, Long.toString(file.fileSize()));
            add(digest, request.title());
            add(digest, request.description());
            add(digest, request.visibility().name());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new DocGridException(ErrorCode.FILE_HASH_CALCULATION_FAILED, e);
        }
    }

    /**
     * 최초 소유권 획득, 완료 결과 재사용, 입력 충돌 및 진행 중 요청을 판정한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim acquire(Long userId, String key, String fingerprint) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_KEY_INVALID);
        }

        // 1. 사용자와 키의 DB 유일 제약으로 앱 인스턴스 사이에서 첫 처리자를 한 명만 정한다.
        UUID token = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
            INSERT INTO initial_upload_requests
                (user_id, request_key, input_fingerprint, state, owner_token, lease_expires_at)
            VALUES (?, ?, ?, 'IN_PROGRESS', ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
            ON CONFLICT (user_id, request_key) DO NOTHING
            """, userId, key, fingerprint, token);
        if (inserted == 1) {
            return new Claim(userId, key, token, null, null);
        }

        // 2. 기존 키의 입력이 다르면 완료 여부와 관계없이 같은 작업으로 취급하지 않는다.
        RequestRow row = jdbcTemplate.queryForObject("""
            SELECT input_fingerprint, state, document_id, document_version_id,
                   file_object_id, embedding_job_id, document_status, job_status,
                   candidate_storage_provider, candidate_bucket_name, candidate_object_key,
                   candidate_file_hash, candidate_file_size, candidate_generation
              FROM initial_upload_requests WHERE user_id = ? AND request_key = ?
            """, (rs, ignored) -> new RequestRow(
            rs.getString("input_fingerprint"), rs.getString("state"),
            rs.getObject("document_id", Long.class), rs.getObject("document_version_id", Long.class),
            rs.getObject("file_object_id", Long.class), rs.getObject("embedding_job_id", Long.class),
            rs.getString("document_status"), rs.getString("job_status"),
            rs.getString("candidate_object_key") == null ? null : new Candidate(
                new StoredFile(StorageProvider.valueOf(rs.getString("candidate_storage_provider")),
                    rs.getString("candidate_bucket_name"), rs.getString("candidate_object_key")),
                rs.getString("candidate_file_hash"), rs.getLong("candidate_file_size"),
                rs.getObject("candidate_generation", Long.class)
            )
        ), userId, key);
        if (!fingerprint.equals(row.fingerprint())) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_KEY_CONFLICT);
        }
        if ("COMPLETED".equals(row.state())) {
            return new Claim(userId, key, null, new DocumentUploadResponse(
                row.documentId(), row.versionId(), row.fileObjectId(), row.jobId(),
                DocumentStatus.valueOf(row.documentStatus()), EmbeddingJobStatus.valueOf(row.jobStatus())
            ), null);
        }

        // 3. 만료된 소유권만 비교 후 교체한다. 경합에서 진 요청은 저장소에 접근하지 않는다.
        int reclaimed = jdbcTemplate.update("""
            UPDATE initial_upload_requests
               SET owner_token = ?, lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND state = 'IN_PROGRESS'
               AND lease_expires_at <= CURRENT_TIMESTAMP
            """, token, userId, key);
        if (reclaimed == 1) {
            return new Claim(userId, key, token, null, row.candidate());
        }
        throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_IN_PROGRESS);
    }

    /**
     * 문서·버전·Outbox·Job과 동일한 트랜잭션 안에서 최초 응답을 확정한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, DocumentUploadResponse response, boolean candidateClaimed) {
        int updated = jdbcTemplate.update("""
            UPDATE initial_upload_requests
               SET state = 'COMPLETED', document_id = ?, document_version_id = ?, file_object_id = ?,
                   embedding_job_id = ?, document_status = ?, job_status = ?, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND owner_token = ? AND state = 'IN_PROGRESS'
            """, response.documentId(), response.documentVersionId(), response.fileObjectId(),
            response.embeddingJobId(), response.documentStatus().name(), response.jobStatus().name(),
            claim.userId(), claim.key(), claim.token());
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
        if (candidateClaimed) {
            // FileObject가 채택한 원본은 정리 후보에서 제외한다. 이 변경도 문서 생성과 함께 커밋된다.
            jdbcTemplate.update("""
                UPDATE initial_upload_requests
                   SET candidate_storage_provider = NULL, candidate_bucket_name = NULL,
                       candidate_object_key = NULL, candidate_file_hash = NULL, candidate_file_size = NULL,
                       candidate_generation = NULL, candidate_registered_at = NULL
                 WHERE user_id = ? AND request_key = ? AND owner_token = ?
                """, claim.userId(), claim.key(), claim.token());
        }
    }

    /** 파일 저장 전에 소유자 토큰을 확인하고 새 후보 위치·예상 바이너리 정보를 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerCandidate(Claim claim, StoredFile candidate, String fileHash, long fileSize) {
        int updated = jdbcTemplate.update("""
            UPDATE initial_upload_requests
               SET candidate_storage_provider = ?, candidate_bucket_name = ?, candidate_object_key = ?,
                   candidate_file_hash = ?, candidate_file_size = ?, candidate_generation = NULL,
                   candidate_registered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND owner_token = ? AND state = 'IN_PROGRESS'
               AND candidate_cleanup_token IS NULL AND candidate_object_key IS NULL
            """, candidate.storageProvider().name(), candidate.bucketName(), candidate.objectKey(),
            fileHash, fileSize, claim.userId(), claim.key(), claim.token());
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
    }

    /** 저장 완료 후 generation을 알게 되면 같은 후보에만 기록한다. 중단 시 이 값은 재검사로 복구한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCandidateGeneration(Claim claim, StoredFile candidate, long generation) {
        int updated = jdbcTemplate.update("""
            UPDATE initial_upload_requests SET candidate_generation = ?, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND owner_token = ? AND state = 'IN_PROGRESS'
               AND candidate_object_key = ?
            """, generation, claim.userId(), claim.key(), claim.token(), candidate.objectKey());
        if (updated != 1) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_OWNERSHIP_LOST);
        }
    }

    /**
     * 실패가 확인된 현재 소유자의 리스를 만료시켜 동일 입력의 다음 요청이 즉시 재시도하게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Claim claim) {
        jdbcTemplate.update("""
            UPDATE initial_upload_requests
               SET lease_expires_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND owner_token = ? AND state = 'IN_PROGRESS'
            """, claim.userId(), claim.key(), claim.token());
    }

    /** 유예 기간이 지난 후보만 찾는다. 조회만 할 때에는 소유권과 저장소 상태를 바꾸지 않는다. */
    public List<CleanupTarget> findCleanupTargets(Duration gracePeriod, int limit) {
        if (gracePeriod.isNegative() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid cleanup scan options");
        }
        return jdbcTemplate.query("""
            SELECT user_id, request_key, candidate_storage_provider, candidate_bucket_name,
                   candidate_object_key, candidate_file_hash, candidate_file_size, candidate_generation
              FROM initial_upload_requests
             WHERE candidate_object_key IS NOT NULL
               AND candidate_registered_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
               AND (state = 'COMPLETED' OR (state IN ('IN_PROGRESS', 'CLEANING')
                    AND lease_expires_at <= CURRENT_TIMESTAMP))
               AND (candidate_cleanup_lease_expires_at IS NULL
                    OR candidate_cleanup_lease_expires_at <= CURRENT_TIMESTAMP)
             ORDER BY candidate_registered_at, user_id, request_key LIMIT ?
            """, (rs, ignored) -> new CleanupTarget(
            rs.getLong("user_id"), rs.getString("request_key"), new Candidate(
                new StoredFile(StorageProvider.valueOf(rs.getString("candidate_storage_provider")),
                    rs.getString("candidate_bucket_name"), rs.getString("candidate_object_key")),
                rs.getString("candidate_file_hash"), rs.getLong("candidate_file_size"),
                rs.getObject("candidate_generation", Long.class)
            )
        ), gracePeriod.toSeconds(), limit);
    }

    /** DB 커밋으로 이전 업로더를 무효화한 다음에만 외부 객체 삭제를 허용한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CleanupClaim reserveCleanup(CleanupTarget target, Duration gracePeriod) {
        UUID cleanupToken = UUID.randomUUID();
        int updated = jdbcTemplate.update("""
            UPDATE initial_upload_requests
               SET state = CASE WHEN state = 'IN_PROGRESS' THEN 'CLEANING' ELSE state END,
                   owner_token = ?, candidate_cleanup_token = ?,
                   candidate_cleanup_lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                   updated_at = CURRENT_TIMESTAMP
             WHERE user_id = ? AND request_key = ? AND candidate_object_key = ?
               AND candidate_registered_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
               AND (state = 'COMPLETED' OR (state IN ('IN_PROGRESS', 'CLEANING')
                    AND lease_expires_at <= CURRENT_TIMESTAMP))
               AND (candidate_cleanup_lease_expires_at IS NULL
                    OR candidate_cleanup_lease_expires_at <= CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), cleanupToken, target.userId(), target.key(),
            target.candidate().storedFile().objectKey(), gracePeriod.toSeconds());
        return updated == 1 ? new CleanupClaim(target, cleanupToken) : null;
    }

    /** 완료된 FileObject가 해당 외부 객체를 참조하는지 삭제 직전에 확인한다. */
    public boolean isReferenced(CleanupClaim claim) {
        StoredFile file = claim.target().candidate().storedFile();
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM file_objects
             WHERE storage_provider = ? AND bucket_name = ? AND object_key = ?
            """, Integer.class, file.storageProvider().name(), file.bucketName(), file.objectKey());
        return count != null && count > 0;
    }

    /** 삭제 성공 또는 참조 확인 시 후보만 제거하고 완료 응답은 유지한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCleanup(CleanupClaim claim, boolean removeCandidate) {
        int updated;
        if (removeCandidate) {
            updated = jdbcTemplate.update("""
                UPDATE initial_upload_requests
                   SET state = CASE WHEN state = 'CLEANING' THEN 'IN_PROGRESS' ELSE state END,
                       lease_expires_at = CURRENT_TIMESTAMP,
                       candidate_storage_provider = NULL, candidate_bucket_name = NULL,
                       candidate_object_key = NULL, candidate_file_hash = NULL,
                       candidate_file_size = NULL, candidate_generation = NULL,
                       candidate_registered_at = NULL, candidate_cleanup_token = NULL,
                       candidate_cleanup_lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ? AND request_key = ? AND candidate_cleanup_token = ?
                """, claim.target().userId(), claim.target().key(), claim.cleanupToken());
        } else {
            updated = jdbcTemplate.update("""
                UPDATE initial_upload_requests
                   SET state = CASE WHEN state = 'CLEANING' THEN 'IN_PROGRESS' ELSE state END,
                       lease_expires_at = CURRENT_TIMESTAMP,
                       candidate_cleanup_token = NULL, candidate_cleanup_lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE user_id = ? AND request_key = ? AND candidate_cleanup_token = ?
                """, claim.target().userId(), claim.target().key(), claim.cleanupToken());
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

    /** 최초 업로드의 현재 소유권 또는 이미 확정된 원본 응답이다. */
    public record Claim(
        Long userId, String key, UUID token, DocumentUploadResponse completedResponse, Candidate candidate
    ) {
    }

    /** 중단된 시도의 저장 위치와 요청에서 계산한 원본 바이너리 지문이다. */
    public record Candidate(StoredFile storedFile, String fileHash, long fileSize, Long generation) {
    }

    /** 정리 조회에서 발견한 후보와 요청 원장의 내부 식별자다. 외부 로그에는 기록하지 않는다. */
    public record CleanupTarget(Long userId, String key, Candidate candidate) {
    }

    /** 정리 예약의 소유권 토큰이다. 토큰을 가진 작업만 DB의 후보 상태를 확정할 수 있다. */
    public record CleanupClaim(CleanupTarget target, UUID cleanupToken) {
    }

    /** DB 원장의 한 시점 상태를 읽어 입력 비교와 완료 응답 재구성에만 사용하는 값이다. */
    private record RequestRow(
        String fingerprint, String state,
        Long documentId, Long versionId, Long fileObjectId, Long jobId,
        String documentStatus, String jobStatus, Candidate candidate
    ) {
    }
}
