package com.opensource.docgrid.domain.document.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.FileStorageType;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.repository.FileObjectRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentUploadService 단위 테스트")
class DocumentUploadServiceTest {

    private static final Long USER_ID = 1L;
    private static final String FILE_HASH = "hash";
    private static final UUID SOURCE_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000162");

    @Mock private UserRepository userRepository;
    @Mock private FileObjectRepository fileObjectRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private EmbeddingModelQueryService embeddingModelQueryService;
    @Mock private SyncEventWriter syncEventWriter;
    @Mock private InitialUploadRequestService initialUploadRequestService;
    @Mock private SyncOutboxEvent syncOutboxEvent;

    private DocumentUploadService documentUploadService;
    private User user;
    private FileObject fileObject;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        FileStorageProperties fileStorageProperties = new FileStorageProperties();
        fileStorageProperties.setType(FileStorageType.MINIO);
        fileStorageProperties.setBucket("bucket");
        documentUploadService = new DocumentUploadService(
            userRepository,
            new FileObjectResolutionService(fileObjectRepository, fileStorageProperties),
            documentRepository,
            documentVersionRepository,
            embeddingJobRepository,
            embeddingModelQueryService,
            syncEventWriter,
            initialUploadRequestService
        );
        user = User.builder()
            .email("user@test.com")
            .passwordHash("hash")
            .name("사용자")
            .status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        fileObject = FileObject.builder()
            .bucketName("bucket")
            .objectKey("object-key")
            .originalFilename("sample.txt")
            .contentType("text/plain")
            .fileSize(4L)
            .fileHash(FILE_HASH)
            .storageProvider(StorageProvider.MINIO)
            .uploadedBy(user)
            .build();
        ReflectionTestUtils.setField(fileObject, "id", 3L);
        embeddingModel = EmbeddingModelFixture.createDefaultModel();
        ReflectionTestUtils.setField(embeddingModel, "id", 5L);
    }

    @Test
    @DisplayName("기존 FileObject로 문서, 첫 버전, PENDING Job을 생성한다")
    void upload_createsDocumentVersionAndJob_withExistingFileObject() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.getReferenceById(USER_ID)).willReturn(user);
        given(fileObjectRepository.findById(3L)).willReturn(Optional.of(fileObject));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> withId(invocation.getArgument(0), 10L));
        given(documentVersionRepository.save(any(DocumentVersion.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 11L));
        given(embeddingModelQueryService.getActiveModel()).willReturn(embeddingModel);
        givenSourceEvent();
        given(embeddingJobRepository.save(any(EmbeddingJob.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 12L));

        DocumentUploadTransactionResult result = documentUploadService.upload(command(3L, null));

        assertThat(result.candidateClaimed()).isFalse();
        assertThat(result.response().documentId()).isEqualTo(10L);
        assertThat(result.response().documentVersionId()).isEqualTo(11L);
        assertThat(result.response().fileObjectId()).isEqualTo(3L);
        assertThat(result.response().embeddingJobId()).isEqualTo(12L);
        assertThat(result.response().documentStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(result.response().jobStatus()).isEqualTo(EmbeddingJobStatus.PENDING);

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        ArgumentCaptor<EmbeddingJob> jobCaptor = ArgumentCaptor.forClass(EmbeddingJob.class);
        then(documentRepository).should().save(documentCaptor.capture());
        then(documentVersionRepository).should().save(versionCaptor.capture());
        then(embeddingJobRepository).should().save(jobCaptor.capture());

        assertThat(documentCaptor.getValue().getCurrentVersion()).isSameAs(versionCaptor.getValue());
        assertThat(versionCaptor.getValue().getVersionNo()).isEqualTo(1);
        assertThat(versionCaptor.getValue().getFileObject()).isSameAs(fileObject);
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(jobCaptor.getValue().getPriority()).isZero();
        assertThat(jobCaptor.getValue().getRetryCount()).isZero();
        assertThat(jobCaptor.getValue().getMaxRetryCount()).isEqualTo(3);
        assertThat(jobCaptor.getValue().getEmbeddingModel()).isSameAs(embeddingModel);
        assertThat(jobCaptor.getValue().getSourceEventId()).isEqualTo(SOURCE_EVENT_ID);
        then(syncEventWriter).should().recordDocumentVersionCreated(versionCaptor.getValue(), embeddingModel);
    }

    @Test
    @DisplayName("동시 경합에서 insert하지 못하면 기존 FileObject를 재조회한다")
    void upload_reusesFileObject_when_atomicInsertLosesRace() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepository.getReferenceById(USER_ID)).willReturn(user);
        given(fileObjectRepository.insertIfAbsent(
            "bucket", "candidate-key", "sample.txt", "text/plain", 4L, FILE_HASH, "MINIO", USER_ID
        )).willReturn(0);
        given(fileObjectRepository.findByFileHashAndFileSize(FILE_HASH, 4L)).willReturn(Optional.of(fileObject));
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> withId(invocation.getArgument(0), 10L));
        given(documentVersionRepository.save(any(DocumentVersion.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 11L));
        given(embeddingModelQueryService.getActiveModel()).willReturn(embeddingModel);
        givenSourceEvent();
        given(embeddingJobRepository.save(any(EmbeddingJob.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 12L));

        DocumentUploadTransactionResult result = documentUploadService.upload(
            command(null, new StoredFile(StorageProvider.MINIO, "bucket", "candidate-key"))
        );

        assertThat(result.candidateClaimed()).isFalse();
        assertThat(result.response().fileObjectId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("사용자가 존재하지 않으면 예외가 발생하고 문서를 저장하지 않는다")
    void upload_throws_when_userDoesNotExist() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> documentUploadService.upload(command(3L, null)))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

        then(documentRepository).shouldHaveNoInteractions();
    }

    private DocumentUploadCommand command(Long existingFileObjectId, StoredFile storedFile) {
        return new DocumentUploadCommand(
            USER_ID,
            "title",
            "description",
            VisibilityType.PRIVATE,
            new ValidatedFile("sample.txt", "txt", "text/plain", 4L, DocumentType.TXT),
            FILE_HASH,
            existingFileObjectId,
            storedFile
        );
    }

    private void givenSourceEvent() {
        given(syncEventWriter.recordDocumentVersionCreated(any(DocumentVersion.class), any(EmbeddingModel.class)))
            .willReturn(syncOutboxEvent);
        given(syncOutboxEvent.getEventId()).willReturn(SOURCE_EVENT_ID);
    }

    private <T> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
