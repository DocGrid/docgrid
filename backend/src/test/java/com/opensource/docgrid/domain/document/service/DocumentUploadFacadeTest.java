package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.io.InputStream;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentUploadFacade 단위 테스트")
class DocumentUploadFacadeTest {

    private static final Long USER_ID = 1L;
    private static final String FILE_HASH = "hash";

    @Mock private FileValidationService fileValidationService;
    @Mock private FileHashService fileHashService;
    @Mock private FileStorageService fileStorageService;
    @Mock private DocumentUploadService documentUploadService;
    @Mock private InitialUploadRequestService initialUploadRequestService;
    @Mock private FileStorageProperties fileStorageProperties;

    private DocumentUploadFacade documentUploadFacade;
    private DocumentUploadRequest request;
    private ValidatedFile validatedFile;

    @BeforeEach
    void setUp() {
        documentUploadFacade = new DocumentUploadFacade(
            fileValidationService, fileHashService, fileStorageService, documentUploadService,
            initialUploadRequestService, fileStorageProperties
        );
        MockMultipartFile file = new MockMultipartFile("file", "sample.txt", "text/plain", "text".getBytes());
        request = new DocumentUploadRequest(file, "title", "description", VisibilityType.PRIVATE);
        validatedFile = new ValidatedFile("sample.txt", "txt", "text/plain", 4L, DocumentType.TXT);
        given(fileValidationService.validate(file)).willReturn(validatedFile);
        given(fileHashService.calculateSha256(file)).willReturn(FILE_HASH);
    }

    @Test
    @DisplayName("기존 FileObject가 있으면 MinIO 업로드 없이 재사용한다")
    void upload_reusesExistingFileObject() {
        DocumentUploadResponse response = response();
        given(documentUploadService.findReusableFileObjectId(FILE_HASH, 4L)).willReturn(Optional.of(3L));
        given(documentUploadService.upload(any(DocumentUploadCommand.class)))
            .willReturn(new DocumentUploadTransactionResult(response, false));

        DocumentUploadResponse result = documentUploadFacade.upload(USER_ID, request);

        assertThat(result).isEqualTo(response);
        then(fileStorageService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("MinIO 저장 후 DB 작업이 실패하면 후보 Object를 삭제한다")
    void upload_deletesCandidate_when_databaseFails() {
        StoredFile candidate = new StoredFile(StorageProvider.MINIO, "bucket", "object-key");
        RuntimeException databaseFailure = new RuntimeException("db failure");
        given(documentUploadService.findReusableFileObjectId(FILE_HASH, 4L)).willReturn(Optional.empty());
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(candidate);
        given(documentUploadService.upload(any(DocumentUploadCommand.class))).willThrow(databaseFailure);

        assertThatThrownBy(() -> documentUploadFacade.upload(USER_ID, request)).isSameAs(databaseFailure);

        then(fileStorageService).should().delete(candidate);
    }

    @Test
    @DisplayName("동시 경합에서 후보가 채택되지 않으면 후보 Object를 삭제한다")
    void upload_deletesCandidate_when_raceIsLost() {
        StoredFile candidate = new StoredFile(StorageProvider.MINIO, "bucket", "object-key");
        given(documentUploadService.findReusableFileObjectId(FILE_HASH, 4L)).willReturn(Optional.empty());
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(candidate);
        given(documentUploadService.upload(any(DocumentUploadCommand.class)))
            .willReturn(new DocumentUploadTransactionResult(response(), false));

        documentUploadFacade.upload(USER_ID, request);

        then(fileStorageService).should().delete(candidate);
    }

    @Test
    @DisplayName("보상 삭제 실패가 원래 DB 예외를 덮어쓰지 않는다")
    void upload_preservesOriginalException_when_cleanupFails() {
        StoredFile candidate = new StoredFile(StorageProvider.MINIO, "bucket", "object-key");
        RuntimeException databaseFailure = new RuntimeException("db failure");
        given(documentUploadService.findReusableFileObjectId(FILE_HASH, 4L)).willReturn(Optional.empty());
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willReturn(candidate);
        given(documentUploadService.upload(any(DocumentUploadCommand.class))).willThrow(databaseFailure);
        givenCleanupFailure(candidate);

        assertThatThrownBy(() -> documentUploadFacade.upload(USER_ID, request)).isSameAs(databaseFailure);
    }

    private void givenCleanupFailure(StoredFile candidate) {
        org.mockito.BDDMockito.willThrow(new RuntimeException("cleanup failure"))
            .given(fileStorageService).delete(candidate);
    }

    private DocumentUploadResponse response() {
        return new DocumentUploadResponse(1L, 2L, 3L, 4L,
            DocumentStatus.UPLOADED, EmbeddingJobStatus.PENDING);
    }
}
