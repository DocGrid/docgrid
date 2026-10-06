package com.opensource.docgrid.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import com.opensource.docgrid.domain.user.entity.Department;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.CommonStatus;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.DepartmentRepository;
import com.opensource.docgrid.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * 공개 범위 DEPARTMENT 문서의 열람 판정 SQL을 실제 PostgreSQL로 검증한다.
 *
 * <p>검색 pre-filter 두 쿼리와 단건 판정 쿼리({@code existsDepartmentVisibleToUser})가 같은 조건
 * (소유자와 같은 부서, READ 전용)으로 동작하는지 확인한다. 서비스 로직과 권한 부여 경로는 검증하지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("DEPARTMENT 공개 범위 Repository 테스트")
class DocumentDepartmentVisibilityRepositoryTest {

    private static final List<String> INDEXED_ONLY = List.of(DocumentStatus.INDEXED.name());

    @Autowired private DocumentRepository documentRepository;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private CollectionDocumentRepository collectionDocumentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("소유자와 같은 부서의 사용자는 DEPARTMENT 문서를 검색 대상과 단건 판정에서 읽을 수 있다")
    void sameDepartment_canReadDepartmentDocument() {
        Department department = saveDepartment();
        User owner = saveUser(department);
        User colleague = saveUser(department);
        Document document = saveDocument(owner, VisibilityType.DEPARTMENT);
        DocumentCollection collection = saveCollection(owner);
        addToCollection(collection, document, owner);
        flushAndClear();

        assertThat(documentRepository.findReadableDocumentIds(colleague.getId(), INDEXED_ONLY))
            .contains(document.getId());
        assertThat(documentRepository.findReadableDocumentIdsInCollection(
            colleague.getId(), collection.getId(), INDEXED_ONLY)).containsExactly(document.getId());
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), colleague.getId())).isTrue();
    }

    @Test
    @DisplayName("다른 부서의 사용자는 DEPARTMENT 문서를 읽을 수 없다")
    void otherDepartment_cannotReadDepartmentDocument() {
        User owner = saveUser(saveDepartment());
        User outsider = saveUser(saveDepartment());
        Document document = saveDocument(owner, VisibilityType.DEPARTMENT);
        flushAndClear();

        assertThat(documentRepository.findReadableDocumentIds(outsider.getId(), INDEXED_ONLY))
            .doesNotContain(document.getId());
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), outsider.getId())).isFalse();
    }

    @Test
    @DisplayName("부서가 없는 사용자와 부서가 없는 소유자끼리는 같은 부서로 보지 않는다")
    void usersWithoutDepartment_areNotTreatedAsSameDepartment() {
        User owner = saveUser(null);
        User otherWithoutDepartment = saveUser(null);
        User memberOfSomeDepartment = saveUser(saveDepartment());
        Document document = saveDocument(owner, VisibilityType.DEPARTMENT);
        flushAndClear();

        assertThat(documentRepository.findReadableDocumentIds(otherWithoutDepartment.getId(), INDEXED_ONLY))
            .doesNotContain(document.getId());
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), otherWithoutDepartment.getId()))
            .isFalse();
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), memberOfSomeDepartment.getId()))
            .isFalse();
    }

    @Test
    @DisplayName("같은 부서라도 PRIVATE 문서는 읽을 수 없다")
    void sameDepartment_cannotReadPrivateDocument() {
        Department department = saveDepartment();
        User owner = saveUser(department);
        User colleague = saveUser(department);
        Document document = saveDocument(owner, VisibilityType.PRIVATE);
        flushAndClear();

        assertThat(documentRepository.findReadableDocumentIds(colleague.getId(), INDEXED_ONLY))
            .doesNotContain(document.getId());
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), colleague.getId())).isFalse();
    }

    @Test
    @DisplayName("삭제된 DEPARTMENT 문서는 검색 대상에서 제외한다")
    void deletedDepartmentDocument_isExcluded() {
        Department department = saveDepartment();
        User owner = saveUser(department);
        User colleague = saveUser(department);
        Document document = saveDocument(owner, VisibilityType.DEPARTMENT);
        document.markDeleted(LocalDateTime.now());
        flushAndClear();

        assertThat(documentRepository.findReadableDocumentIds(colleague.getId(), INDEXED_ONLY))
            .doesNotContain(document.getId());
    }

    @Test
    @DisplayName("사용자의 부서가 바뀌면 판정도 바로 따라간다 (실시간 판정)")
    void departmentChange_isReflectedImmediately() {
        Department engineering = saveDepartment();
        Department finance = saveDepartment();
        User owner = saveUser(engineering);
        User member = saveUser(engineering);
        Document document = saveDocument(owner, VisibilityType.DEPARTMENT);
        flushAndClear();
        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), member.getId())).isTrue();

        User managedMember = userRepository.findById(member.getId()).orElseThrow();
        managedMember.changeDepartment(departmentRepository.findById(finance.getId()).orElseThrow());
        flushAndClear();

        assertThat(documentRepository.existsDepartmentVisibleToUser(document.getId(), member.getId())).isFalse();
        assertThat(documentRepository.findReadableDocumentIds(member.getId(), INDEXED_ONLY))
            .doesNotContain(document.getId());
    }

    private Department saveDepartment() {
        return departmentRepository.save(
            Department.builder()
                .name("DEPARTMENT 공개 범위 테스트 부서")
                .code("DV-DEPT-" + UUID.randomUUID())
                .status(CommonStatus.ACTIVE)
                .build()
        );
    }

    private User saveUser(Department department) {
        return userRepository.save(
            User.builder()
                .department(department)
                .email("dept-visibility-" + UUID.randomUUID() + "@test.com")
                .passwordHash("hash")
                .name("DEPARTMENT 공개 범위 테스트 사용자")
                .status(UserStatus.ACTIVE)
                .build()
        );
    }

    private Document saveDocument(User owner, VisibilityType visibility) {
        return documentRepository.save(
            Document.builder()
                .owner(owner)
                .title("DEPARTMENT 공개 범위 테스트 문서")
                .documentType(DocumentType.TXT)
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.INDEXED)
                .visibility(visibility)
                .build()
        );
    }

    private DocumentCollection saveCollection(User owner) {
        DocumentCollection saved = collectionRepository.save(
            DocumentCollection.builder()
                .owner(owner)
                .name("DEPARTMENT 공개 범위 테스트 컬렉션")
                .visibility(VisibilityType.PRIVATE)
                .build()
        );
        collectionRepository.insertClosureForNewCollection(saved.getId(), null);
        return saved;
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
