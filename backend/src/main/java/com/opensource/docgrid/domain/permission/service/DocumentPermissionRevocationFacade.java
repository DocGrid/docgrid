package com.opensource.docgrid.domain.permission.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.domain.permission.service.command.DocumentPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 회수를 요청 ID에 묶고 새 WAL을 동기 커밋해 결과 불명을 재확정한다.
 * strict 동기 클러스터가 운영 전제이며 일반 비동기 환경은 기존 회수 경로를 유지한다.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DocumentPermissionRevocationFacade {
    private final DocumentPermissionCommandService commands;
    private final PermissionQueryService queries;
    private final DocumentPermissionRepository permissions;
    private final UserDocumentAccessCacheRepository caches;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final boolean synchronousEnabled;

    public DocumentPermissionRevocationFacade(DocumentPermissionCommandService commands,
            PermissionQueryService queries, DocumentPermissionRepository permissions,
            UserDocumentAccessCacheRepository caches, JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            @Value("${permission.revocation.synchronous-enabled:false}") boolean synchronousEnabled) {
        this.commands = commands;
        this.queries = queries;
        this.permissions = permissions;
        this.caches = caches;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.synchronousEnabled = synchronousEnabled;
    }

    public void revoke(Long documentId, Long permissionId, Long actorId, UUID requestId) {
        if (!synchronousEnabled) {
            commands.revokePermission(documentId, permissionId, actorId);
            return;
        }
        if (requestId == null) throw new DocGridException(ErrorCode.INVALID_PARAMETER);
        try {
            SynchronousCommitTransactionManager.guarded(() -> transaction.execute(status -> {
                // 1. 같은 물리 연결의 Primary와 동기 후보 설정을 검증한다.
                Boolean configured = jdbc.queryForObject("SELECT NOT pg_is_in_recovery() AND current_setting('synchronous_standby_names') <> ''", Boolean.class);
                if (!Boolean.TRUE.equals(configured)) {
                    throw new DocGridException(ErrorCode.PERMISSION_REVOCATION_UNCONFIRMED);
                }
                jdbc.execute("SET LOCAL synchronous_commit=on");
                jdbc.execute("SET LOCAL statement_timeout='5s'");

                // 2. 같은 키의 경합을 DB 행 잠금으로 직렬화하고 다른 대상 재사용은 거부한다.
                boolean created = jdbc.update("INSERT INTO permission_revocation_requests (request_id, actor_id, document_id, permission_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    requestId, actorId, documentId, permissionId) == 1;
                var binding = jdbc.queryForMap("SELECT actor_id, document_id, permission_id FROM permission_revocation_requests WHERE request_id=? FOR UPDATE", requestId);
                if (!actorId.equals(((Number) binding.get("actor_id")).longValue())
                        || !documentId.equals(((Number) binding.get("document_id")).longValue())
                        || !permissionId.equals(((Number) binding.get("permission_id")).longValue())) {
                    throw new DocGridException(ErrorCode.PERMISSION_REVOCATION_KEY_CONFLICT);
                }
                if (!queries.canAdminDocument(actorId, documentId)) {
                    throw new DocGridException(ErrorCode.PERMISSION_DENIED);
                }

                // 3. 최초 요청은 원장·캐시·Outbox를 함께 변경한다. 재주장도 캐시를 다시 무효화한다.
                if (created || permissions.existsById(permissionId)) {
                    commands.revokePermission(documentId, permissionId, actorId);
                } else {
                    caches.bulkInvalidateBySource(AccessSourceType.DIRECT_DOCUMENT_PERMISSION, permissionId);
                }
                // 4. DELETE 0행이나 단순 조회와 달리 실제 새 WAL을 생성해 앞선 회수까지 재확정한다.
                jdbc.update("UPDATE permission_revocation_requests SET assertion_count=assertion_count+1, last_asserted_at=clock_timestamp() WHERE request_id=?", requestId);
                return null;
            }));
        } catch (TransactionException | org.springframework.dao.DataAccessException exception) {
            throw new DocGridException(ErrorCode.PERMISSION_REVOCATION_UNCONFIRMED, exception);
        }
    }
}
