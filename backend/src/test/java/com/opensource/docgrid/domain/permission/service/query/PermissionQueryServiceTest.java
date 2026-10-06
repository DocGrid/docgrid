package com.opensource.docgrid.domain.permission.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.fixture.CollectionFixture;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionSummaryResponse;
import com.opensource.docgrid.domain.permission.entity.CollectionPermission;
import com.opensource.docgrid.domain.permission.entity.DocumentPermission;
import com.opensource.docgrid.domain.permission.enums.PermissionSourceType;
import com.opensource.docgrid.domain.permission.repository.CollectionPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.DocumentPermissionRepository;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서·컬렉션 권한 계산과 ADMIN 전용 직접 권한 목록 조회 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionQueryService 단위 테스트")
class PermissionQueryServiceTest {

    @InjectMocks
    private PermissionQueryService service;

    @Mock private CollectionRepository collectionRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private UserDocumentAccessCacheRepository cacheRepository;
    @Mock private DocumentPermissionRepository documentPermissionRepository;
    @Mock private CollectionPermissionRepository collectionPermissionRepository;
    @Mock private PermissionConverter permissionConverter;

    // ==================== canReadDocument ====================

    @Test
    @DisplayName("소유자는 canReadDocument가 true다")
    void canReadDocument_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canReadDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidReadCache(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("PUBLIC 문서는 누구나 canReadDocument가 true다")
    void canReadDocument_public_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.PUBLIC);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("유효한 USER 캐시가 있으면 canReadDocument가 true다")
    void canReadDocument_validCache_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canReadDocument가 true다")
    void canReadDocument_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canReadDocument가 true다")
    void canReadDocument_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("부모 컬렉션에만 ROLE/DEPARTMENT 권한이 있어도 canReadDocument가 true다 (상속)")
    void canReadDocument_inheritedFromParentCollection_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        List<Long> effectiveCollectionIds = List.of(1L, 2L);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionRepository.findEffectiveCollectionIdsForDocument(CollectionFixture.DOCUMENT_ID)).willReturn(effectiveCollectionIds);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollections(otherUserId, effectiveCollectionIds)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("모든 단계를 통과하지 못하면 canReadDocument가 false다")
    void canReadDocument_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("문서가 없으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void canReadDocument_documentNotFound_throwsException() {
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.canReadDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    // ==================== canWriteDocument ====================

    @Test
    @DisplayName("소유자는 canWriteDocument가 true다")
    void canWriteDocument_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canWriteDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidWriteCache(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("유효한 USER 캐시가 있으면 canWriteDocument가 true다")
    void canWriteDocument_validCache_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canWriteDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsRoleWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canWriteDocument가 true다")
    void canWriteDocument_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canWriteDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsDeptWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canWriteDocument가 true다")
    void canWriteDocument_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canWriteDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("부모 컬렉션에만 ROLE/DEPARTMENT 권한이 있어도 canWriteDocument가 true다 (상속)")
    void canWriteDocument_inheritedFromParentCollection_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        List<Long> effectiveCollectionIds = List.of(1L, 2L);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionRepository.findEffectiveCollectionIdsForDocument(CollectionFixture.DOCUMENT_ID)).willReturn(effectiveCollectionIds);
        given(collectionPermissionRepository.existsDeptWritePermissionForCollections(otherUserId, effectiveCollectionIds)).willReturn(true);

        boolean result = service.canWriteDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("모든 단계를 통과하지 못하면 canWriteDocument가 false다")
    void canWriteDocument_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);

        boolean result = service.canWriteDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isFalse();
    }

    // ==================== canAdminDocument ====================

    @Test
    @DisplayName("소유자는 canAdminDocument가 true다")
    void canAdminDocument_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        boolean result = service.canAdminDocument(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidAdminCache(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("유효한 USER 캐시가 있으면 canAdminDocument가 true다")
    void canAdminDocument_validCache_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidAdminCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canAdminDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsRoleAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canAdminDocument가 true다")
    void canAdminDocument_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidAdminCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canAdminDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(documentPermissionRepository).should(never()).existsDeptAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canAdminDocument가 true다")
    void canAdminDocument_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidAdminCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        boolean result = service.canAdminDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("부모 컬렉션에만 ROLE/DEPARTMENT 권한이 있어도 canAdminDocument가 true다 (상속)")
    void canAdminDocument_inheritedFromParentCollection_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        List<Long> effectiveCollectionIds = List.of(1L, 2L);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidAdminCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionRepository.findEffectiveCollectionIdsForDocument(CollectionFixture.DOCUMENT_ID)).willReturn(effectiveCollectionIds);
        given(collectionPermissionRepository.existsDeptAdminPermissionForCollections(otherUserId, effectiveCollectionIds)).willReturn(true);

        boolean result = service.canAdminDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("모든 단계를 통과하지 못하면 canAdminDocument가 false다")
    void canAdminDocument_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidAdminCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsRoleAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(documentPermissionRepository.existsDeptAdminPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForDocument(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(false);

        boolean result = service.canAdminDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isFalse();
    }

    // ==================== canReadCollection ====================

    @Test
    @DisplayName("컬렉션 소유자는 canReadCollection이 true다")
    void canReadCollection_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));

        boolean result = service.canReadCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
        then(collectionPermissionRepository).should(never()).existsUserReadPermission(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);
    }

    @Test
    @DisplayName("PUBLIC 컬렉션은 누구나 canReadCollection이 true다")
    void canReadCollection_public_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(collection, "visibility", VisibilityType.PUBLIC);
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
        then(collectionPermissionRepository).should(never()).existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID);
    }

    @Test
    @DisplayName("USER 권한으로 read가 부여된 경우 canReadCollection이 true다")
    void canReadCollection_userPermission_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canReadCollection이 true다")
    void canReadCollection_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canReadCollection이 true다")
    void canReadCollection_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조상 컬렉션에만 DEPARTMENT 권한이 있어도 canReadCollection이 true다 (상속)")
    void canReadCollection_inheritedFromAncestor_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        List<Long> ancestorIds = List.of(CollectionFixture.COLLECTION_ID, 1L);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionRepository.findAncestorIdsInclusive(CollectionFixture.COLLECTION_ID)).willReturn(ancestorIds);
        given(collectionPermissionRepository.existsRoleReadPermissionForCollections(otherUserId, ancestorIds)).willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollections(otherUserId, ancestorIds)).willReturn(true);

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("권한이 없으면 canReadCollection이 false다")
    void canReadCollection_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserReadPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);

        boolean result = service.canReadCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("컬렉션이 없으면 canReadCollection 호출 시 COLLECTION_NOT_FOUND 예외가 발생한다")
    void canReadCollection_collectionNotFound_throwsException() {
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.canReadCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 컬렉션이면 canReadCollection 호출 시 COLLECTION_NOT_FOUND 예외가 발생한다")
    void canReadCollection_collectionDeleted_throwsException() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(collection, "status", com.opensource.docgrid.domain.collection.enums.CollectionStatus.DELETED);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));

        assertThatThrownBy(() -> service.canReadCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 컬렉션 엔티티를 직접 넘기면 소유자여도 canReadCollection 호출 시 COLLECTION_NOT_FOUND 예외가 발생한다")
    void canReadCollection_entityOverload_rejectsDeletedCollection_evenForOwner() {
        User owner = CollectionFixture.createOwner();
        DocumentCollection collection = CollectionFixture.createCollection(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(collection, "status", com.opensource.docgrid.domain.collection.enums.CollectionStatus.DELETED);

        assertThatThrownBy(() -> service.canReadCollection(CollectionFixture.USER_ID, collection))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COLLECTION_NOT_FOUND);
    }

    // ==================== canWriteCollection ====================

    @Test
    @DisplayName("컬렉션 소유자는 canWriteCollection이 true다")
    void canWriteCollection_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));

        boolean result = service.canWriteCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
        then(collectionPermissionRepository).should(never()).existsUserWritePermission(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);
    }

    @Test
    @DisplayName("USER 권한으로 write가 부여된 경우 canWriteCollection이 true다")
    void canWriteCollection_userPermission_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserWritePermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canWriteCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canWriteCollection이 true다")
    void canWriteCollection_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserWritePermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canWriteCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canWriteCollection이 true다")
    void canWriteCollection_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserWritePermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canWriteCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조상 컬렉션에만 DEPARTMENT 권한이 있어도 canWriteCollection이 true다 (상속)")
    void canWriteCollection_inheritedFromAncestor_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        List<Long> ancestorIds = List.of(CollectionFixture.COLLECTION_ID, 1L);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserWritePermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionRepository.findAncestorIdsInclusive(CollectionFixture.COLLECTION_ID)).willReturn(ancestorIds);
        given(collectionPermissionRepository.existsRoleWritePermissionForCollections(otherUserId, ancestorIds)).willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForCollections(otherUserId, ancestorIds)).willReturn(true);

        boolean result = service.canWriteCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("권한이 없으면 canWriteCollection이 false다")
    void canWriteCollection_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserWritePermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptWritePermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);

        boolean result = service.canWriteCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isFalse();
    }

    // ==================== DEPARTMENT 공개 범위 (2-1단계) ====================

    @Test
    @DisplayName("DEPARTMENT 공개 문서는 소유자와 같은 부서면 canReadDocument가 true다")
    void canReadDocument_departmentVisibility_sameDepartment_returnsTrue() {
        Document document = CollectionFixture.createDocument(CollectionFixture.createOwner());
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.DEPARTMENT);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentRepository.existsDepartmentVisibleToUser(CollectionFixture.DOCUMENT_ID, otherUserId)).willReturn(true);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isTrue();
        then(cacheRepository).should(never()).existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("DEPARTMENT 공개 문서라도 소유자와 다른 부서면 다음 단계로 넘어가 모두 없으면 false다")
    void canReadDocument_departmentVisibility_otherDepartment_fallsThroughToFalse() {
        Document document = CollectionFixture.createDocument(CollectionFixture.createOwner());
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.DEPARTMENT);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentRepository.existsDepartmentVisibleToUser(CollectionFixture.DOCUMENT_ID, otherUserId)).willReturn(false);

        boolean result = service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result).isFalse();
        then(cacheRepository).should().existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("PRIVATE 문서는 같은 부서 여부를 조회하지 않는다")
    void canReadDocument_privateVisibility_doesNotCheckDepartment() {
        Document document = CollectionFixture.createDocument(CollectionFixture.createOwner());
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        service.canReadDocument(otherUserId, CollectionFixture.DOCUMENT_ID);

        then(documentRepository).should(never()).existsDepartmentVisibleToUser(CollectionFixture.DOCUMENT_ID, otherUserId);
    }

    @Test
    @DisplayName("DEPARTMENT 공개 문서는 같은 부서면 canRead만 true이고 sources에 DEPARTMENT_VISIBILITY가 포함된다")
    void checkDocumentPermission_departmentVisibility_returnsReadOnlyWithSource() {
        Document document = CollectionFixture.createDocument(CollectionFixture.createOwner());
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.DEPARTMENT);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentRepository.existsDepartmentVisibleToUser(CollectionFixture.DOCUMENT_ID, otherUserId)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.canWrite()).isFalse();
        assertThat(result.canAdmin()).isFalse();
        assertThat(result.sources()).contains(PermissionSourceType.DEPARTMENT_VISIBILITY);
    }

    // ==================== checkDocumentPermission ====================

    @Test
    @DisplayName("소유자는 canRead/canWrite/canAdmin 모두 true이고 sources에 OWNER만 포함된다")
    void checkDocumentPermission_owner_returnsAllTrueWithOwnerSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.canWrite()).isTrue();
        assertThat(result.canAdmin()).isTrue();
        assertThat(result.sources()).containsExactly(PermissionSourceType.OWNER);
        then(cacheRepository).should(never()).existsValidReadCache(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID);
    }

    @Test
    @DisplayName("PUBLIC 문서는 canRead가 true이고 sources에 PUBLIC이 포함된다")
    void checkDocumentPermission_public_returnsReadTrueWithPublicSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        org.springframework.test.util.ReflectionTestUtils.setField(document, "visibility", VisibilityType.PUBLIC);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.canWrite()).isFalse();
        assertThat(result.canAdmin()).isFalse();
        assertThat(result.sources()).containsExactly(PermissionSourceType.PUBLIC);
    }

    @Test
    @DisplayName("USER_CACHE로 쓰기 권한이 있으면 canWrite가 true이고 sources에 USER_CACHE가 포함된다")
    void checkDocumentPermission_userCache_returnsWriteTrueWithCacheSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidReadCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.canWrite()).isTrue();
        assertThat(result.canAdmin()).isFalse();
        assertThat(result.sources()).containsExactly(PermissionSourceType.USER_CACHE);
    }

    @Test
    @DisplayName("ROLE 권한이 있으면 해당 권한이 true이고 sources에 ROLE이 포함된다")
    void checkDocumentPermission_role_returnsPermissionWithRoleSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.sources()).contains(PermissionSourceType.ROLE);
        assertThat(result.sources()).doesNotContain(PermissionSourceType.DEPARTMENT);
    }

    @Test
    @DisplayName("DEPARTMENT 권한이 있으면 해당 권한이 true이고 sources에 DEPARTMENT가 포함된다")
    void checkDocumentPermission_dept_returnsPermissionWithDeptSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentPermissionRepository.existsDeptWritePermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canWrite()).isTrue();
        assertThat(result.sources()).containsExactly(PermissionSourceType.DEPARTMENT);
    }

    @Test
    @DisplayName("부모 컬렉션에만 DEPARTMENT 권한이 있어도 canRead가 true이고 sources에 DEPARTMENT가 포함된다 (상속)")
    void checkDocumentPermission_inheritedFromParentCollection_returnsReadTrueWithDeptSource() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        List<Long> effectiveCollectionIds = List.of(1L, 2L);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(collectionRepository.findEffectiveCollectionIdsForDocument(CollectionFixture.DOCUMENT_ID)).willReturn(effectiveCollectionIds);
        given(collectionPermissionRepository.existsDeptReadPermissionForCollections(otherUserId, effectiveCollectionIds)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.sources()).containsExactly(PermissionSourceType.DEPARTMENT);
    }

    @Test
    @DisplayName("ROLE과 USER_CACHE가 동시에 충족되면 sources에 둘 다 포함된다")
    void checkDocumentPermission_multipleSource_returnsBothSources() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(cacheRepository.existsValidWriteCache(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);
        given(documentPermissionRepository.existsRoleReadPermission(otherUserId, CollectionFixture.DOCUMENT_ID)).willReturn(true);

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isTrue();
        assertThat(result.canWrite()).isTrue();
        assertThat(result.sources()).containsExactlyInAnyOrder(
                PermissionSourceType.USER_CACHE, PermissionSourceType.ROLE);
    }

    @Test
    @DisplayName("권한이 전혀 없으면 모두 false이고 sources가 비어있다")
    void checkDocumentPermission_noPermission_returnsAllFalseEmptySources() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        DocumentPermissionSummaryResponse result =
                service.checkDocumentPermission(otherUserId, CollectionFixture.DOCUMENT_ID);

        assertThat(result.canRead()).isFalse();
        assertThat(result.canWrite()).isFalse();
        assertThat(result.canAdmin()).isFalse();
        assertThat(result.sources()).isEmpty();
    }

    @Test
    @DisplayName("문서가 없으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void checkDocumentPermission_documentNotFound_throwsException() {
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.checkDocumentPermission(CollectionFixture.USER_ID, CollectionFixture.DOCUMENT_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    // ==================== canAdminCollection ====================

    @Test
    @DisplayName("컬렉션 소유자는 canAdminCollection이 true다")
    void canAdminCollection_owner_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));

        boolean result = service.canAdminCollection(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
        then(collectionPermissionRepository).should(never()).existsUserAdminPermission(CollectionFixture.USER_ID, CollectionFixture.COLLECTION_ID);
    }

    @Test
    @DisplayName("USER 권한으로 admin이 부여된 경우 canAdminCollection이 true다")
    void canAdminCollection_userPermission_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ROLE live 권한이 있으면 canAdminCollection이 true다")
    void canAdminCollection_roleLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("DEPARTMENT live 권한이 있으면 canAdminCollection이 true다")
    void canAdminCollection_deptLive_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(true);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조상 컬렉션에만 DEPARTMENT 권한이 있어도 canAdminCollection이 true다 (상속)")
    void canAdminCollection_inheritedFromAncestor_returnsTrue() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        List<Long> ancestorIds = List.of(CollectionFixture.COLLECTION_ID, 1L);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionRepository.findAncestorIdsInclusive(CollectionFixture.COLLECTION_ID)).willReturn(ancestorIds);
        given(collectionPermissionRepository.existsRoleAdminPermissionForCollections(otherUserId, ancestorIds)).willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForCollections(otherUserId, ancestorIds)).willReturn(true);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("권한이 없으면 canAdminCollection이 false다")
    void canAdminCollection_noPermission_returnsFalse() {
        User owner = CollectionFixture.createOwner();
        Long otherUserId = 99L;
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID))
                .willReturn(Optional.of(CollectionFixture.createCollection(owner)));
        given(collectionPermissionRepository.existsUserAdminPermission(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsRoleAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);
        given(collectionPermissionRepository.existsDeptAdminPermissionForCollection(otherUserId, CollectionFixture.COLLECTION_ID))
                .willReturn(false);

        boolean result = service.canAdminCollection(otherUserId, CollectionFixture.COLLECTION_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("문서 소유자는 직접 부여된 문서 권한 전체를 조회한다")
    void getDocumentPermissions_returnsDirectPermissions_whenUserCanAdmin() {
        User owner = CollectionFixture.createOwner();
        Document document = CollectionFixture.createDocument(owner);
        DocumentPermission permission = org.mockito.Mockito.mock(DocumentPermission.class);
        DocumentPermissionResponse response = org.mockito.Mockito.mock(DocumentPermissionResponse.class);
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentPermissionRepository.findAllWithTargetsByDocumentId(CollectionFixture.DOCUMENT_ID))
                .willReturn(List.of(permission));
        given(permissionConverter.toDocumentPermissionResponse(permission)).willReturn(response);

        List<DocumentPermissionResponse> result = service.getDocumentPermissions(
                CollectionFixture.USER_ID,
                CollectionFixture.DOCUMENT_ID
        );

        assertThat(result).containsExactly(response);
    }

    @Test
    @DisplayName("컬렉션 소유자는 직접 부여된 컬렉션 권한 전체를 조회한다")
    void getCollectionPermissions_returnsDirectPermissions_whenUserCanAdmin() {
        DocumentCollection collection = CollectionFixture.createCollection();
        CollectionPermission permission = org.mockito.Mockito.mock(CollectionPermission.class);
        CollectionPermissionResponse response = org.mockito.Mockito.mock(CollectionPermissionResponse.class);
        given(collectionRepository.findById(CollectionFixture.COLLECTION_ID)).willReturn(Optional.of(collection));
        given(collectionPermissionRepository.findAllWithTargetsByCollectionId(CollectionFixture.COLLECTION_ID))
                .willReturn(List.of(permission));
        given(permissionConverter.toCollectionPermissionResponse(permission)).willReturn(response);

        List<CollectionPermissionResponse> result = service.getCollectionPermissions(
                CollectionFixture.USER_ID,
                CollectionFixture.COLLECTION_ID
        );

        assertThat(result).containsExactly(response);
    }

    @Test
    @DisplayName("문서 ADMIN 권한이 없으면 직접 권한 목록을 조회하지 않는다")
    void getDocumentPermissions_throwsBeforeListQuery_whenAdminPermissionIsDenied() {
        Document document = CollectionFixture.createDocument(CollectionFixture.createOwner());
        Long otherUserId = 99L;
        given(documentRepository.findById(CollectionFixture.DOCUMENT_ID)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> service.getDocumentPermissions(otherUserId, CollectionFixture.DOCUMENT_ID))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
        then(documentPermissionRepository).should(never())
                .findAllWithTargetsByDocumentId(CollectionFixture.DOCUMENT_ID);
    }
}
