package com.opensource.docgrid.domain.permission.service.command;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.enums.CollectionStatus;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.AccessSourceType;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.sync.enums.SyncPermissionOperation;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

@Transactional
@Service
@RequiredArgsConstructor
public class CollectionPermissionCommandService {

    private final CollectionRepository collectionRepository;
    private final CollectionDocumentRepository collectionDocumentRepository;
    private final CollectionPermissionRepository collectionPermissionRepository;
    private final UserDocumentAccessCacheService cacheService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionConverter permissionConverter;
    private final PermissionQueryService permissionQueryService;
    private final SyncEventWriter syncEventWriter;

    // 컬렉션 권한 부여
    public CollectionPermissionResponse grantPermission(Long collectionId, Long grantorId,
                                                        GrantPermissionRequest request) {
        DocumentCollection collection = collectionRepository.findById(collectionId)
                .filter(c -> c.getStatus() != CollectionStatus.DELETED)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_NOT_FOUND));

        if (!permissionQueryService.canAdminCollection(grantorId, collection)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        validateTargetType(request); // targetType과 ID 필드 조합 유효성 검사

        User targetUser = null;
        Role targetRole = null;
        Department targetDepartment = null;

        if (request.targetType() == PermissionTargetType.USER) {
            targetUser = userRepository.findById(request.userId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        } else if (request.targetType() == PermissionTargetType.ROLE) {
            targetRole = roleRepository.findById(request.roleId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.ROLE_NOT_FOUND));
            // 모든 사용자가 기본으로 가진 USER role을 대상으로 지정하면 사실상 전체 공개가 되므로 차단한다.
            if ("USER".equals(targetRole.getCode())) {
                throw new DocGridException(ErrorCode.ROLE_NOT_GRANTABLE);
            }
        } else {
            targetDepartment = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new DocGridException(ErrorCode.DEPARTMENT_NOT_FOUND));
        }

        if (hasActiveGrant(collectionId, request.targetType(), targetUser, targetRole, targetDepartment)) {
            throw new DocGridException(ErrorCode.COLLECTION_PERMISSION_ALREADY_GRANTED);
        }

        boolean[] permissions = resolvePermissions(request.permissionType());
        // 부여자는 프록시가 아니라 실제 엔티티로 읽는다. USER 대상 권한은 벌크 UPDATE(clearAutomatically)가
        // 영속성 컨텍스트를 비워서, 프록시면 응답 변환 시 getName()이 LazyInitializationException으로 실패한다.
        User grantor = userRepository.findById(grantorId)
                .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        CollectionPermission permission = CollectionPermission.builder()
                .collection(collection)
                .targetType(request.targetType())
                .user(targetUser)
                .role(targetRole)
                .department(targetDepartment)
                .permissionType(request.permissionType())
                .canRead(permissions[0])
                .canWrite(permissions[1])
                .canAdmin(permissions[2])
                .grantedBy(grantor)
                .grantedAt(LocalDateTime.now())
                .expiresAt(request.expiresAt())
                .build();

        collectionPermissionRepository.save(permission);

        if (request.targetType() == PermissionTargetType.USER) {
            updateCacheForCollection(collectionId, targetUser, permissions, permission.getId(), request.expiresAt());
        }

        // 권한 원장과 같은 Transaction에 컬렉션 캐시 재투영 의도를 기록한다.
        syncEventWriter.recordPermissionCacheRefresh(
            AccessSourceType.DIRECT_COLLECTION_PERMISSION,
            permission.getId(),
            SyncPermissionOperation.GRANTED
        );

        return permissionConverter.toCollectionPermissionResponse(permission);
    }

    // 컬렉션 권한 회수
    public void revokePermission(Long collectionId, Long permissionId, Long revokerId) {
        CollectionPermission permission = collectionPermissionRepository.findById(permissionId)
                .orElseThrow(() -> new DocGridException(ErrorCode.COLLECTION_PERMISSION_NOT_FOUND));

        DocumentCollection collection = permission.getCollection();
        if (!collection.getId().equals(collectionId)) {
            throw new DocGridException(ErrorCode.COLLECTION_PERMISSION_NOT_FOUND);
        }

        if (!permissionQueryService.canAdminCollection(revokerId, collectionId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }

        if (permission.getTargetType() == PermissionTargetType.USER) {
            cacheService.bulkRevokeBySource(AccessSourceType.DIRECT_COLLECTION_PERMISSION, permissionId);
        }

        collectionPermissionRepository.delete(permission);
        syncEventWriter.recordPermissionCacheRefresh(
            AccessSourceType.DIRECT_COLLECTION_PERMISSION,
            permissionId,
            SyncPermissionOperation.REVOKED
        );
    }

    // 이 대상에게 만료되지 않은 권한이 이미 있는지 확인 (permissionType 무관 — 중복 부여 방지)
    private boolean hasActiveGrant(Long collectionId, PermissionTargetType targetType,
                                    User targetUser, Role targetRole, Department targetDepartment) {
        return switch (targetType) {
            case USER -> collectionPermissionRepository.existsActiveUserGrant(collectionId, targetUser.getId());
            case ROLE -> collectionPermissionRepository.existsActiveRoleGrant(collectionId, targetRole.getId());
            case DEPARTMENT -> collectionPermissionRepository.existsActiveDeptGrant(collectionId, targetDepartment.getId());
        };
    }

    // targetType과 ID 필드 조합 유효성 검사
    private void validateTargetType(GrantPermissionRequest request) {
        boolean valid = switch (request.targetType()) {
            case USER -> request.userId() != null && request.roleId() == null && request.departmentId() == null;
            case ROLE -> request.roleId() != null && request.userId() == null && request.departmentId() == null;
            case DEPARTMENT -> request.departmentId() != null && request.userId() == null && request.roleId() == null;
        };
        if (!valid) {
            throw new DocGridException(ErrorCode.INVALID_TARGET_TYPE);
        }
    }

    // PermissionType → canRead/canWrite/canAdmin 변환 (WRITE는 READ 포함, ADMIN은 전체 포함)
    private boolean[] resolvePermissions(PermissionType type) {
        return switch (type) {
            case READ  -> new boolean[]{true, false, false};
            case WRITE -> new boolean[]{true, true, false};
            case ADMIN -> new boolean[]{true, true, true};
        };
    }

    // USER 권한 부여 시 컬렉션 내 모든 문서에 캐시 일괄 갱신 (N+1 방지)
    private void updateCacheForCollection(Long collectionId, User targetUser, boolean[] permissions,
                                          Long sourceId, LocalDateTime expiresAt) {
        List<Document> documents = collectionDocumentRepository.findAllByCollectionId(collectionId)
                .stream().map(CollectionDocument::getDocument).toList();
        cacheService.bulkGrantUserPermission(targetUser, documents,
                permissions[0], permissions[1], permissions[2],
                AccessSourceType.DIRECT_COLLECTION_PERMISSION, sourceId, expiresAt);
    }
}
