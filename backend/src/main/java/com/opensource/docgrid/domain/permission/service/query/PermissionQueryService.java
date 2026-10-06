package com.opensource.docgrid.domain.permission.service.query;

import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionSummaryResponse;
import com.opensource.docgrid.domain.permission.enums.PermissionSourceType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class PermissionQueryService {

    private final CollectionRepository collectionRepository;
    private final DocumentRepository documentRepository;
    private final UserDocumentAccessCacheRepository cacheRepository;
    private final DocumentPermissionRepository documentPermissionRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;
    private final PermissionConverter permissionConverter;

    /**
     * 문서 ADMIN 권한 보유자에게 문서에 직접 부여된 전체 권한을 반환한다.
     */
    public List<DocumentPermissionResponse> getDocumentPermissions(Long userId, Long documentId) {
        // 1. 목록 조회 전에 소유권·직접·상속 ADMIN 권한을 공통 규칙으로 검증한다.
        if (!canAdminDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 만료된 기록도 회수·감사 화면에서 관리할 수 있도록 직접 부여 기록 전체를 반환한다.
        return documentPermissionRepository.findAllWithTargetsByDocumentId(documentId).stream()
                .map(permissionConverter::toDocumentPermissionResponse)
                .toList();
    }

    /**
     * 컬렉션 ADMIN 권한 보유자에게 컬렉션에 직접 부여된 전체 권한을 반환한다.
     */
    public List<CollectionPermissionResponse> getCollectionPermissions(Long userId, Long collectionId) {
        // 1. 삭제 컬렉션 차단과 ADMIN 권한 판단을 기존 컬렉션 권한 규칙에 위임한다.
        if (!canAdminCollection(userId, collectionId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        // 2. 계산·상속 권한은 포함하지 않고 회수 API가 참조할 직접 권한 ID만 반환한다.
        return collectionPermissionRepository.findAllWithTargetsByCollectionId(collectionId).stream()
                .map(permissionConverter::toCollectionPermissionResponse)
                .toList();
    }

    // 문서 읽기 권한 판단 (6단계 + DEPARTMENT 공개 범위 2-1단계)
    public boolean canReadDocument(Long userId, Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 1단계: 소유자
        if (document.getOwner().getId().equals(userId)) {
            return true;
        }

        // 2단계: PUBLIC
        if (document.getVisibility() == VisibilityType.PUBLIC) {
            return true;
        }

        // 2-1단계: DEPARTMENT 공개 범위 — 소유자와 같은 부서의 사용자는 읽기만 허용
        if (document.getVisibility() == VisibilityType.DEPARTMENT
                && documentRepository.existsDepartmentVisibleToUser(documentId, userId)) {
            return true;
        }

        // 3단계: USER 캐시
        if (cacheRepository.existsValidReadCache(userId, documentId)) {
            return true;
        }

        // 4단계: ROLE live
        if (documentPermissionRepository.existsRoleReadPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId)) {
            return true;
        }

        // 5단계: DEPARTMENT live
        if (documentPermissionRepository.existsDeptReadPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId)) {
            return true;
        }

        // 6단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
        return !effectiveCollectionIds.isEmpty()
                && (collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, effectiveCollectionIds)
                        || collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, effectiveCollectionIds));
    }

    // 문서 쓰기 권한 판단 (5단계)
    public boolean canWriteDocument(Long userId, Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 1단계: 소유자
        if (document.getOwner().getId().equals(userId)) {
            return true;
        }

        // 2단계: USER 캐시
        if (cacheRepository.existsValidWriteCache(userId, documentId)) {
            return true;
        }

        // 3단계: ROLE live
        if (documentPermissionRepository.existsRoleWritePermission(userId, documentId)
                || collectionPermissionRepository.existsRoleWritePermissionForDocument(userId, documentId)) {
            return true;
        }

        // 4단계: DEPARTMENT live
        if (documentPermissionRepository.existsDeptWritePermission(userId, documentId)
                || collectionPermissionRepository.existsDeptWritePermissionForDocument(userId, documentId)) {
            return true;
        }

        // 5단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
        return !effectiveCollectionIds.isEmpty()
                && (collectionPermissionRepository.existsRoleWritePermissionForCollections(userId, effectiveCollectionIds)
                        || collectionPermissionRepository.existsDeptWritePermissionForCollections(userId, effectiveCollectionIds));
    }

    // 문서 관리 권한 판단 (5단계)
    public boolean canAdminDocument(Long userId, Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 1단계: 소유자
        if (document.getOwner().getId().equals(userId)) {
            return true;
        }

        // 2단계: USER 캐시
        if (cacheRepository.existsValidAdminCache(userId, documentId)) {
            return true;
        }

        // 3단계: ROLE live
        if (documentPermissionRepository.existsRoleAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleAdminPermissionForDocument(userId, documentId)) {
            return true;
        }

        // 4단계: DEPARTMENT live
        if (documentPermissionRepository.existsDeptAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptAdminPermissionForDocument(userId, documentId)) {
            return true;
        }

        // 5단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
        return !effectiveCollectionIds.isEmpty()
                && (collectionPermissionRepository.existsRoleAdminPermissionForCollections(userId, effectiveCollectionIds)
                        || collectionPermissionRepository.existsDeptAdminPermissionForCollections(userId, effectiveCollectionIds));
    }

    // 문서 권한 확인 API용 — read/write/admin 동시 판단 + 접근 경로(sources) 수집
    public DocumentPermissionSummaryResponse checkDocumentPermission(Long userId, Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        List<PermissionSourceType> sources = new ArrayList<>();
        boolean canRead = false, canWrite = false, canAdmin = false;

        // 1단계: 소유자 — 전체 권한 즉시 반환
        if (document.getOwner().getId().equals(userId)) {
            sources.add(PermissionSourceType.OWNER);
            return new DocumentPermissionSummaryResponse(documentId, true, true, true, sources);
        }

        // 2단계: PUBLIC — 읽기만 허용
        if (document.getVisibility() == VisibilityType.PUBLIC) {
            canRead = true;
            sources.add(PermissionSourceType.PUBLIC);
        }

        // 2-1단계: DEPARTMENT 공개 범위 — 소유자와 같은 부서의 사용자는 읽기만 허용
        if (document.getVisibility() == VisibilityType.DEPARTMENT
                && documentRepository.existsDepartmentVisibleToUser(documentId, userId)) {
            canRead = true;
            sources.add(PermissionSourceType.DEPARTMENT_VISIBILITY);
        }

        // 3단계: USER 캐시
        boolean cacheRead  = cacheRepository.existsValidReadCache(userId, documentId);
        boolean cacheWrite = cacheRepository.existsValidWriteCache(userId, documentId);
        boolean cacheAdmin = cacheRepository.existsValidAdminCache(userId, documentId);
        if (cacheRead || cacheWrite || cacheAdmin) {
            sources.add(PermissionSourceType.USER_CACHE);
            if (cacheRead)  canRead  = true;
            if (cacheWrite) canWrite = true;
            if (cacheAdmin) canAdmin = true;
        }

        // 4단계: ROLE live
        boolean roleRead  = documentPermissionRepository.existsRoleReadPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleReadPermissionForDocument(userId, documentId);
        boolean roleWrite = documentPermissionRepository.existsRoleWritePermission(userId, documentId)
                || collectionPermissionRepository.existsRoleWritePermissionForDocument(userId, documentId);
        boolean roleAdmin = documentPermissionRepository.existsRoleAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsRoleAdminPermissionForDocument(userId, documentId);
        if (roleRead || roleWrite || roleAdmin) {
            sources.add(PermissionSourceType.ROLE);
            if (roleRead)  canRead  = true;
            if (roleWrite) canWrite = true;
            if (roleAdmin) canAdmin = true;
        }

        // 5단계: DEPARTMENT live
        boolean deptRead  = documentPermissionRepository.existsDeptReadPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptReadPermissionForDocument(userId, documentId);
        boolean deptWrite = documentPermissionRepository.existsDeptWritePermission(userId, documentId)
                || collectionPermissionRepository.existsDeptWritePermissionForDocument(userId, documentId);
        boolean deptAdmin = documentPermissionRepository.existsDeptAdminPermission(userId, documentId)
                || collectionPermissionRepository.existsDeptAdminPermissionForDocument(userId, documentId);
        if (deptRead || deptWrite || deptAdmin) {
            sources.add(PermissionSourceType.DEPARTMENT);
            if (deptRead)  canRead  = true;
            if (deptWrite) canWrite = true;
            if (deptAdmin) canAdmin = true;
        }

        // 6단계: 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT) — 기존 ROLE/DEPARTMENT 출처 값을 그대로 재사용한다
        List<Long> effectiveCollectionIds = collectionRepository.findEffectiveCollectionIdsForDocument(documentId);
        if (!effectiveCollectionIds.isEmpty()) {
            boolean inheritedRoleRead  = collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, effectiveCollectionIds);
            boolean inheritedRoleWrite = collectionPermissionRepository.existsRoleWritePermissionForCollections(userId, effectiveCollectionIds);
            boolean inheritedRoleAdmin = collectionPermissionRepository.existsRoleAdminPermissionForCollections(userId, effectiveCollectionIds);
            boolean inheritedDeptRead  = collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, effectiveCollectionIds);
            boolean inheritedDeptWrite = collectionPermissionRepository.existsDeptWritePermissionForCollections(userId, effectiveCollectionIds);
            boolean inheritedDeptAdmin = collectionPermissionRepository.existsDeptAdminPermissionForCollections(userId, effectiveCollectionIds);
            if ((inheritedRoleRead || inheritedRoleWrite || inheritedRoleAdmin) && !sources.contains(PermissionSourceType.ROLE)) {
                sources.add(PermissionSourceType.ROLE);
            }
            if ((inheritedDeptRead || inheritedDeptWrite || inheritedDeptAdmin) && !sources.contains(PermissionSourceType.DEPARTMENT)) {
                sources.add(PermissionSourceType.DEPARTMENT);
            }
            if (inheritedRoleRead || inheritedDeptRead) canRead = true;
            if (inheritedRoleWrite || inheritedDeptWrite) canWrite = true;
            if (inheritedRoleAdmin || inheritedDeptAdmin) canAdmin = true;
        }

        return new DocumentPermissionSummaryResponse(documentId, canRead, canWrite, canAdmin, sources);
    }

    // 컬렉션 읽기 권한 판단 (소유자, PUBLIC, USER/ROLE/DEPT 직접 권한) — ID로 조회 후 엔티티 버전에 위임
    public boolean canReadCollection(Long userId, Long collectionId) {
        return canReadCollection(userId, getActiveCollection(collectionId));
    }

    // 이미 조회된 컬렉션 엔티티로 판단 — soft-delete된 엔티티면 COLLECTION_NOT_FOUND
    public boolean canReadCollection(Long userId, DocumentCollection collection) {
        validateActiveCollection(collection);
        if (collection.getOwner().getId().equals(userId)) return true;
        if (collection.getVisibility() == VisibilityType.PUBLIC) return true;
        Long collectionId = collection.getId();
        if (collectionPermissionRepository.existsUserReadPermission(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsRoleReadPermissionForCollection(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsDeptReadPermissionForCollection(userId, collectionId)) return true;

        // 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> ancestorIds = collectionRepository.findAncestorIdsInclusive(collectionId);
        if (collectionPermissionRepository.existsRoleReadPermissionForCollections(userId, ancestorIds)) return true;
        return collectionPermissionRepository.existsDeptReadPermissionForCollections(userId, ancestorIds);
    }

    // 컬렉션 쓰기 권한 판단 (소유자, USER/ROLE/DEPT 직접 권한)
    public boolean canWriteCollection(Long userId, Long collectionId) {
        return canWriteCollection(userId, getActiveCollection(collectionId));
    }

    public boolean canWriteCollection(Long userId, DocumentCollection collection) {
        validateActiveCollection(collection);
        if (collection.getOwner().getId().equals(userId)) return true;
        Long collectionId = collection.getId();
        if (collectionPermissionRepository.existsUserWritePermission(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsRoleWritePermissionForCollection(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsDeptWritePermissionForCollection(userId, collectionId)) return true;

        // 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> ancestorIds = collectionRepository.findAncestorIdsInclusive(collectionId);
        if (collectionPermissionRepository.existsRoleWritePermissionForCollections(userId, ancestorIds)) return true;
        return collectionPermissionRepository.existsDeptWritePermissionForCollections(userId, ancestorIds);
    }

    // 컬렉션 관리 권한 판단 (소유자, USER/ROLE/DEPT 직접 권한)
    public boolean canAdminCollection(Long userId, Long collectionId) {
        return canAdminCollection(userId, getActiveCollection(collectionId));
    }

    public boolean canAdminCollection(Long userId, DocumentCollection collection) {
        validateActiveCollection(collection);
        if (collection.getOwner().getId().equals(userId)) return true;
        Long collectionId = collection.getId();
        if (collectionPermissionRepository.existsUserAdminPermission(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsRoleAdminPermissionForCollection(userId, collectionId)) return true;
        if (collectionPermissionRepository.existsDeptAdminPermissionForCollection(userId, collectionId)) return true;

        // 부모 컬렉션 체인 상속 (ROLE/DEPARTMENT)
        List<Long> ancestorIds = collectionRepository.findAncestorIdsInclusive(collectionId);
        if (collectionPermissionRepository.existsRoleAdminPermissionForCollections(userId, ancestorIds)) return true;
        return collectionPermissionRepository.existsDeptAdminPermissionForCollections(userId, ancestorIds);
    }

    // collectionId로 조회하되, status가 DELETED인 컬렉션은 필터링해서 제외한다 (없는 것으로 취급).
    private DocumentCollection getActiveCollection(Long collectionId) {
        return collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));
    }

    // 엔티티 오버로드 방어 로직 — 호출부가 soft-delete 필터를 빠뜨리고 넘긴 엔티티도 여기서 최종 차단한다(추가 조회 없음).
    private void validateActiveCollection(DocumentCollection collection) {
        if (collection.getStatus() == CollectionStatus.DELETED) {
            throw new DocGridException(ErrorCode.COLLECTION_NOT_FOUND);
        }
    }
}
