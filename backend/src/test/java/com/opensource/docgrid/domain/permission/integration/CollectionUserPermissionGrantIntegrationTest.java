package com.opensource.docgrid.domain.permission.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.collection.entity.CollectionDocument;
import com.opensource.docgrid.domain.collection.entity.DocumentCollection;
import com.opensource.docgrid.domain.collection.repository.CollectionDocumentRepository;
import com.opensource.docgrid.domain.collection.repository.CollectionRepository;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.permission.converter.PermissionConverter;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.repository.UserDocumentAccessCacheRepository;
import com.opensource.docgrid.domain.permission.service.command.CollectionPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.command.UserDocumentAccessCacheService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * 컬렉션 USER 대상 권한 부여가 실제 영속성 컨텍스트에서 끝까지 동작하는지 검증한다.
 *
 * <p>USER 대상 부여는 벌크 UPDATE({@code clearAutomatically = true})가 영속성 컨텍스트를 비우므로,
 * 부여자를 프록시로 읽으면 응답 변환의 {@code getName()}에서 LazyInitializationException이 난다.
 * Mockito 단위 테스트는 이 동작을 재현하지 못해 실제 PostgreSQL로 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    CollectionPermissionCommandService.class,
    UserDocumentAccessCacheService.class,
    PermissionQueryService.class,
    PermissionConverter.class,
    SyncEventWriter.class,
    CollectionUserPermissionGrantIntegrationTest.ClockConfig.class
})
@DisplayName("컬렉션 USER 권한 부여 통합 테스트")
class CollectionUserPermissionGrantIntegrationTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @Autowired private CollectionPermissionCommandService service;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private CollectionDocumentRepository collectionDocumentRepository;
    @Autowired private UserDocumentAccessCacheRepository cacheRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("USER 대상 권한을 부여하면 오류 없이 부여자 이름이 응답에 담기고 컬렉션 문서의 캐시가 생긴다")
    void grantUserPermission_returnsGrantorName_andCachesCollectionDocuments() {
        User grantor = saveUser("부여자");
        User target = saveUser("대상자");
        DocumentCollection collection = saveCollection(grantor);
        Document document = saveDocument(grantor);
        addToCollection(collection, document, grantor);
        // 부여자가 영속성 컨텍스트에 이미 있으면 getReferenceById가 실제 엔티티를 돌려줘 버그가 가려지므로 비운다.
        flushAndClear();

        CollectionPermissionResponse response = service.grantPermission(
            collection.getId(), grantor.getId(), userRead(target.getId()));

        assertThat(response.grantedBy()).isEqualTo(grantor.getId());
        assertThat(response.grantedByName()).isEqualTo("부여자");
        assertThat(response.userId()).isEqualTo(target.getId());
        assertThat(response.userName()).isEqualTo("대상자");
        flushAndClear();
        assertThat(cacheRepository.existsValidReadCache(target.getId(), document.getId())).isTrue();
    }

    @Test
    @DisplayName("문서가 없는 빈 컬렉션에도 USER 대상 권한을 부여할 수 있다")
    void grantUserPermission_worksForEmptyCollection() {
        User grantor = saveUser("부여자");
        User target = saveUser("대상자");
        DocumentCollection collection = saveCollection(grantor);
        flushAndClear();

        CollectionPermissionResponse response = service.grantPermission(
            collection.getId(), grantor.getId(), userRead(target.getId()));

        assertThat(response.permissionId()).isNotNull();
        assertThat(response.grantedByName()).isEqualTo("부여자");
    }

    private GrantPermissionRequest userRead(Long userId) {
        return new GrantPermissionRequest(
            PermissionTargetType.USER, userId, null, null, PermissionType.READ, null);
    }

    private User saveUser(String name) {
        return userRepository.save(
            User.builder()
                .email("collection-user-grant-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name(name)
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private DocumentCollection saveCollection(User owner) {
        DocumentCollection saved = collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .name("USER 권한 부여 테스트 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
        collectionRepository.insertClosureForNewCollection(saved.getId(), null);
        return saved;
    }

    private Document saveDocument(User owner) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title("USER 권한 부여 테스트 문서")
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED)
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
    }

    private void addToCollection(DocumentCollection collection, Document document, User addedBy) {
        collectionDocumentRepository.save(
            CollectionDocument.builder()
                .collection(collection)
                .document(document)
                .addedBy(addedBy)
                .addedAt(LocalDateTime.now())
                .build()
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
