package com.opensource.docgrid.domain.permission.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.fixture.PermissionFixture;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.user.entity.Role;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.RoleRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionPermissionCommandService 단위 테스트")
class CollectionPermissionCommandServiceTest {

    @InjectMocks
    private CollectionPermissionCommandService service;

    @Mock private CollectionRepository collectionRepository;
    @Mock private CollectionDocumentRepository collectionDocumentRepository;
    @Mock private CollectionPermissionRepository collectionPermissionRepository;
    @Mock private UserDocumentAccessCacheService cacheService;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private PermissionConverter permissionConverter;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private SyncEventWriter syncEventWriter;

    // ==================== grantPermission ====================

    @Test
    @DisplayName("USER 대상 권한을 부여하면 캐시도 함께 갱신된다")
    void grantPermission_user_updatesCache() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Document document = CollectionFixture.createDocument(owner);
        CollectionDocument cd = buildCollectionDocument(collection, document);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(userRepository.findById(CollectionFixture.USER_ID)).willReturn(Optional.of(owner));
        given(collectionDocumentRepository.findAllByCollectionId(CollectionFixture.COLLECTION_ID))
                .willReturn(List.of(cd));
        given(permissionConverter.toCollectionPermissionResponse(any())).willReturn(null);

        service.grantPermission(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request);

        then(collectionPermissionRepository).should().save(any(CollectionPermission.class));
        then(cacheService).should().bulkGrantUserPermission(any(), any(), any(boolean.class),
                any(boolean.class), any(boolean.class), any(), any(), any());
    }

    @Test
    @DisplayName("ROLE 대상 권한을 부여하면 캐시 갱신 없이 저장만 된다")
    void grantPermission_role_noCacheUpdate() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Role role = PermissionFixture.createRole();
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, PermissionFixture.ROLE_ID, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(roleRepository.findById(PermissionFixture.ROLE_ID)).willReturn(Optional.of(role));
        given(userRepository.findById(CollectionFixture.USER_ID)).willReturn(Optional.of(owner));
        given(permissionConverter.toCollectionPermissionResponse(any())).willReturn(null);

        service.grantPermission(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request);

        then(collectionPermissionRepository).should().save(any(CollectionPermission.class));
        then(cacheService).should(never()).bulkGrantUserPermission(any(), any(), any(boolean.class),
                any(boolean.class), any(boolean.class), any(), any(), any());
    }

    @Test
    @DisplayName("USER role을 대상으로 지정하면 ROLE_NOT_GRANTABLE 예외가 발생한다")
    void grantPermission_throws_when_targetRoleIsUserRole() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, PermissionFixture.USER_ROLE_ID, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(roleRepository.findById(PermissionFixture.USER_ROLE_ID)).willReturn(Optional.of(PermissionFixture.createUserRole()));

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROLE_NOT_GRANTABLE);
        then(collectionPermissionRepository).should(never()).save(any(CollectionPermission.class));
    }

    @Test
    @DisplayName("컬렉션이 없으면 COLLECTION_NOT_FOUND 예외가 발생한다")
    void grantPermission_throws_when_collectionNotFound() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 권한을 부여하면 PERMISSION_DENIED 예외가 발생한다")
    void grantPermission_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Long otherUserId = 99L;
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, otherUserId, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("USER 대상에게 이미 만료되지 않은 권한이 있으면 COLLECTION_PERMISSION_ALREADY_GRANTED 예외가 발생한다")
    void grantPermission_throws_when_activeUserGrantAlreadyExists() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, CollectionFixture.USER_ID, null, null, PermissionType.WRITE, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(userRepository.findById(CollectionFixture.USER_ID)).willReturn(Optional.of(owner));
        given(collectionPermissionRepository.existsActiveUserGrant(CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID))
                .willReturn(true);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_PERMISSION_ALREADY_GRANTED);
        then(collectionPermissionRepository).should(never()).save(any(CollectionPermission.class));
    }

    @Test
    @DisplayName("ROLE 대상에게 이미 만료되지 않은 권한이 있으면 COLLECTION_PERMISSION_ALREADY_GRANTED 예외가 발생한다")
    void grantPermission_throws_when_activeRoleGrantAlreadyExists() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        Role role = PermissionFixture.createRole();
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.ROLE, null, PermissionFixture.ROLE_ID, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);
        given(roleRepository.findById(PermissionFixture.ROLE_ID)).willReturn(Optional.of(role));
        given(collectionPermissionRepository.existsActiveRoleGrant(CollectionFixture.COLLECTION_ID, PermissionFixture.ROLE_ID))
                .willReturn(true);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_PERMISSION_ALREADY_GRANTED);
        then(collectionPermissionRepository).should(never()).save(any(CollectionPermission.class));
    }

    @Test
    @DisplayName("targetType=USER인데 userId가 null이면 INVALID_TARGET_TYPE 예외가 발생한다")
    void grantPermission_throws_when_invalidTargetType() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        GrantPermissionRequest request = new GrantPermissionRequest(
                PermissionTargetType.USER, null, null, null, PermissionType.READ, null);

        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, collection)).willReturn(true);

        assertThatThrownBy(() -> service.grantPermission(
                CollectionFixture.COLLECTION_ID, CollectionFixture.USER_ID, request))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TARGET_TYPE);
    }

    // ==================== revokePermission ====================

    @Test
    @DisplayName("USER 권한을 회수하면 캐시도 일괄 무효화된다")
    void revokePermission_user_invalidatesCache() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        CollectionPermission permission = PermissionFixture.createCollectionPermission(collection, owner);

        given(collectionPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.of(permission));
        given(permissionQueryService.canAdminCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID)).willReturn(true);

        service.revokePermission(CollectionFixture.COLLECTION_ID, PermissionFixture.PERMISSION_ID,
                CollectionFixture.USER_ID);

        then(cacheService).should().bulkRevokeBySource(any(), any());
        then(collectionPermissionRepository).should().delete(permission);
    }

    @Test
    @DisplayName("권한이 없으면 COLLECTION_PERMISSION_NOT_FOUND 예외가 발생한다")
    void revokePermission_throws_when_permissionNotFound() {
        given(collectionPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokePermission(
                CollectionFixture.COLLECTION_ID, PermissionFixture.PERMISSION_ID, CollectionFixture.USER_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_PERMISSION_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 권한을 회수하면 PERMISSION_DENIED 예외가 발생한다")
    void revokePermission_throws_when_notOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        CollectionPermission permission = PermissionFixture.createCollectionPermission(collection, owner);
        Long otherUserId = 99L;

        given(collectionPermissionRepository.findById(PermissionFixture.PERMISSION_ID))
                .willReturn(Optional.of(permission));

        assertThatThrownBy(() -> service.revokePermission(
                CollectionFixture.COLLECTION_ID, PermissionFixture.PERMISSION_ID, otherUserId))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    private CollectionDocument buildCollectionDocument(DocumentCollection collection, Document document) {
        return CollectionDocument.builder()
                .collection(collection)
                .document(document)
                .addedBy(collection.getOwner())
                .addedAt(java.time.LocalDateTime.now())
                .build();
    }
}
