package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.io.InputStream;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentVersionUploadFacade 단위 테스트")
class DocumentVersionUploadFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long DOCUMENT_ID = 2L;
    private static final String FILE_HASH = "hash";

    @Mock private FileValidationService fileValidationService;
    @Mock private FileHashService fileHashService;
    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentVersionUploadService documentVersionUploadService;
    @Mock private VersionUploadRequestService versionUploadRequestService;

    private DocumentVersionUploadFacade facade;
    private DocumentVersionUploadRequest request;
    private ValidatedFile validatedFile;

    @BeforeEach
    void setUp() {
        facade = new DocumentVersionUploadFacade(
            fileValidationService, fileHashService, fileStorageService, documentVersionUploadService,
            versionUploadRequestService
        );
        MockMultipartFile file = new MockMultipartFile(
            "file", "changed.txt", "text/plain", "text".getBytes()
        );
        request = new DocumentVersionUploadRequest(file);
        validatedFile = new ValidatedFile("changed.txt", "txt", "text/plain", 4L, DocumentType.TXT);
        given(fileValidationService.validate(file)).willReturn(validatedFile);
        given(fileHashService.calculateSha256(file)).willReturn(FILE_HASH);
    }

    @Test
    @DisplayName("기존 FileObject가 있으면 MinIO 작업 없이 새 Version을 생성한다")
    void upload_reusesExistingFileObject_withoutStorageInteraction() {
        given(documentVersionUploadService.prepare(USER_ID, DOCUMENT_ID, validatedFile, FILE_HASH))
            .willReturn(Optional.of(3L));
        given(documentVersionUploadService.upload(any(DocumentVersionUploadCommand.class)))
            .willReturn(new DocumentVersionUploadTransactionResult(response(), false));

        DocumentVersionUploadResponse result = facade.upload(USER_ID, DOCUMENT_ID, request);

        assertThat(result).isEqualTo(response());
        then(fileStorageService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("MinIO 후보 저장 후 DB 작업이 실패하면 후보를 삭제한다")
    void upload_deletesCandidate_when_databaseFails() {
        StoredFile candidate = candidate();
        RuntimeException databaseFailure = new RuntimeException("db failure");
        givenCandidate(candidate);
        given(documentVersionUploadService.upload(any(DocumentVersionUploadCommand.class)))
            .willThrow(databaseFailure);

        assertThatThrownBy(() -> facade.upload(USER_ID, DOCUMENT_ID, request)).isSameAs(databaseFailure);

        then(fileStorageService).should().delete(candidate);
    }

    @Test
    @DisplayName("동시 경합에서 후보가 채택되지 않으면 해당 후보만 삭제한다")
    void upload_deletesCandidate_when_candidateIsNotClaimed() {
        StoredFile candidate = candidate();
        givenCandidate(candidate);
        given(documentVersionUploadService.upload(any(DocumentVersionUploadCommand.class)))
            .willReturn(new DocumentVersionUploadTransactionResult(response(), false));

        DocumentVersionUploadResponse result = facade.upload(USER_ID, DOCUMENT_ID, request);

        assertThat(result).isEqualTo(response());
        then(fileStorageService).should().delete(candidate);
    }

    @Test
    @DisplayName("DB 실패 후 보상 삭제까지 실패해도 원래 DB 예외를 유지한다")
    void upload_preservesDatabaseException_when_cleanupAlsoFails() {
        StoredFile candidate = candidate();
        RuntimeException databaseFailure = new RuntimeException("db failure");
        givenCandidate(candidate);
        given(documentVersionUploadService.upload(any(DocumentVersionUploadCommand.class)))
            .willThrow(databaseFailure);
        willThrow(new RuntimeException("cleanup failure")).given(fileStorageService).delete(candidate);

        assertThatThrownBy(() -> facade.upload(USER_ID, DOCUMENT_ID, request)).isSameAs(databaseFailure);
    }

    @Test
    @DisplayName("DB 성공 후 미사용 후보 삭제가 실패해도 성공 응답을 유지한다")
    void upload_preservesSuccessResponse_when_unusedCandidateCleanupFails() {
        StoredFile candidate = candidate();
        givenCandidate(candidate);
        given(documentVersionUploadService.upload(any(DocumentVersionUploadCommand.class)))
            .willReturn(new DocumentVersionUploadTransactionResult(response(), false));
        willThrow(new RuntimeException("cleanup failure")).given(fileStorageService).delete(candidate);

        DocumentVersionUploadResponse result = facade.upload(USER_ID, DOCUMENT_ID, request);

        assertThat(result).isEqualTo(response());
    }

    @Test
    @DisplayName("MinIO 저장이 실패하면 DB Version 저장을 호출하지 않는다")
    void upload_doesNotCallDatabaseService_when_storageFails() {
        given(documentVersionUploadService.prepare(USER_ID, DOCUMENT_ID, validatedFile, FILE_HASH))
            .willReturn(Optional.empty());
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willThrow(new DocGridException(ErrorCode.FILE_STORAGE_FAILED));

        assertThatThrownBy(() -> facade.upload(USER_ID, DOCUMENT_ID, request))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_STORAGE_FAILED);

        then(documentVersionUploadService).should(never()).upload(any(DocumentVersionUploadCommand.class));
        then(fileStorageService).should(never()).delete(any(StoredFile.class));
    }

    private void givenCandidate(StoredFile candidate) {
        given(documentVersionUploadService.prepare(USER_ID, DOCUMENT_ID, validatedFile, FILE_HASH))
            .willReturn(Optional.empty());
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(candidate);
    }

    private StoredFile candidate() {
        return new StoredFile(StorageProvider.MINIO, "bucket", "object-key");
    }

    private DocumentVersionUploadResponse response() {
        return new DocumentVersionUploadResponse(
            DOCUMENT_ID, 4L, 2, 5L, 3L, DocumentStatus.INDEXED,
            DocumentVersionStatus.UPLOADED, EmbeddingJobStatus.PENDING
        );
    }
}
