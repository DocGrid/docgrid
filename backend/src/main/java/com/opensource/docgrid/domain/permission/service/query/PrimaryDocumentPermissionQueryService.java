package com.opensource.docgrid.domain.permission.service.query;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 새 문서 응답·검색 후보의 최종 읽기 인가를 현재 Primary 원장으로 검증한다.
 * 호출자의 명시적 read-write 트랜잭션에 참여하며 standby·조회 실패를 허용으로 추정하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PrimaryDocumentPermissionQueryService {
    private final EntityManager entityManager;
    private final PermissionQueryService permissions;

    /** 하나의 Primary 연결에서 삭제되지 않은 고유 출처만 현재 권한으로 재인가한다. */
    public Set<Long> findReadableDocumentIds(Long userId, Collection<Long> documentIds) {
        requirePrimary();
        if (documentIds.isEmpty()) return Set.of();
        var existing = entityManager.createQuery(
            "SELECT d.id FROM Document d WHERE d.id IN :ids AND d.deletedAt IS NULL", Long.class)
            .setParameter("ids", documentIds).getResultList();
        Set<Long> readable = new LinkedHashSet<>();
        for (Long id : existing) {
            if (permissions.canReadDocument(userId, id)) readable.add(id);
        }
        return Set.copyOf(readable);
    }

    private void requirePrimary() {
        Object recovery = entityManager.createNativeQuery("SELECT pg_is_in_recovery()").getSingleResult();
        if (!Boolean.FALSE.equals(recovery)) {
            throw new DocGridException(ErrorCode.DOCUMENT_PERMISSION_UNAVAILABLE);
        }
    }

    public boolean canReadDocument(Long userId, Long documentId) {
        // 1. 같은 연결의 역할이 Primary인지 확인한다. read-only 라우팅 오동작도 닫힌 상태로 실패한다.
        requirePrimary();
        // 2. 새 트랜잭션 Snapshot에서 캐시 출처·현재 권한 원장을 함께 검증한다.
        return permissions.canReadDocument(userId, documentId);
    }
}
