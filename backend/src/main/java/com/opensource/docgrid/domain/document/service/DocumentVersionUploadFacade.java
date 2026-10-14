package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentVersionUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.CandidateObjectSnapshot;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기존 문서의 새 버전 업로드에서 파일 저장소와 데이터베이스 작업을 조율한다.
 *
 * <p>권한·파일·문서 상태를 사전 검증해 불필요한 저장소 쓰기를 피하고, 재사용할 FileObject가
 * 없을 때만 새 바이너리 후보를 저장한다. 이후 데이터베이스 변경은
 * {@link DocumentVersionUploadService}에 위임한다. 키 없는 요청은 사용되지 않은 저장소 객체를
 * 보상 삭제한다. 멱등 키가 있으면 저장 전에 후보 위치를 원장에 기록하고, 중단 후 동일 키의
 * 재시도에서 후보 바이트를 확인해 재사용한다. 새 버전·Job·요청 결과는 함께 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionUploadFacade {

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentVersionUploadService documentVersionUploadService;
    private final VersionUploadRequestService versionUploadRequestService;
    private final FileStorageProperties fileStorageProperties;

    /**
     * 기존 문서에 새 파일 버전을 업로드하고 인덱싱 Job을 생성한다.
     *
     * @param userId 새 버전을 등록하는 사용자 ID
     * @param documentId 대상 문서 ID
     * @param request 새 버전 파일을 담은 요청
     * @return 새 버전과 인덱싱 Job 상태
     */
    public DocumentVersionUploadResponse upload(
        Long userId,
        Long documentId,
        DocumentVersionUploadRequest request
    ) {
        return upload(userId, documentId, request, null);
    }

    /** 같은 키·문서·파일의 재시도에는 확정된 새 버전 업로드 응답을 반환한다. */
    public DocumentVersionUploadResponse upload(
        Long userId,
        Long documentId,
        DocumentVersionUploadRequest request,
        String idempotencyKey
    ) {
        // 1. 저장 전에 파일을 검증하고 동일 바이너리 확인용 해시를 계산한다.
        ValidatedFile validatedFile = fileValidationService.validate(request.file());
        String fileHash = fileHashService.calculateSha256(request.file());
        VersionUploadRequestService.Claim claim = null;
        if (idempotencyKey != null) {
            // 2. 완료된 요청은 상태가 이미 바뀌었더라도 버전 생성 검증 전에 재사용한다.
            String fingerprint = versionUploadRequestService.fingerprint(validatedFile, fileHash);
            claim = versionUploadRequestService.acquire(userId, documentId, idempotencyKey, fingerprint);
            if (claim.completedResponse() != null) {
                return claim.completedResponse();
            }
        }

        // 3. 확인된 실패에는 처리권을 풀어 같은 키의 재시도를 허용한다.
        try {
            return uploadClaimed(userId, documentId, request, validatedFile, fileHash, claim);
        } catch (RuntimeException exception) {
            if (claim != null) {
                try {
                    versionUploadRequestService.release(claim);
                } catch (RuntimeException releaseException) {
                    exception.addSuppressed(releaseException);
                }
            }
            throw exception;
        }
    }

    private DocumentVersionUploadResponse uploadClaimed(
        Long userId, Long documentId, DocumentVersionUploadRequest request,
        ValidatedFile validatedFile, String fileHash, VersionUploadRequestService.Claim claim
    ) {
        Optional<Long> existingFileObjectId = documentVersionUploadService.prepare(
            userId, documentId, validatedFile, fileHash
        );

        // 1. 같은 바이너리가 이미 있으면 물리 파일 저장 없이 기존 FileObject로 DB Transaction을 실행한다.
        if (existingFileObjectId.isPresent()) {
            return persist(createCommand(
                userId, documentId, validatedFile, fileHash, existingFileObjectId.get(), null
            ), claim).response();
        }

        // 2. 키가 있으면 저장소 호출 전에 후보 위치를 기록하고 중단된 시도의 후보를 검사한다.
        StoredFile candidate = claim == null
            ? storeCandidate(request, validatedFile, newObjectKey(validatedFile))
            : recoverOrStoreCandidate(claim, request, validatedFile, fileHash);
        DocumentVersionUploadTransactionResult result;

        // 3. 키가 있는 후보는 실패 후 같은 키의 재시도가 사용할 수 있으므로 즉시 삭제하지 않는다.
        try {
            result = persist(createCommand(
                userId, documentId, validatedFile, fileHash, null, candidate
            ), claim);
        } catch (RuntimeException exception) {
            if (claim == null) {
                cleanupCandidate(candidate);
            }
            throw exception;
        }

        // 4. 키 없는 동시 요청의 미사용 후보만 즉시 정리한다. 키가 있으면 정리 원장에 남긴다.
        if (!result.candidateClaimed() && claim == null) {
            cleanupCandidate(candidate);
        }
        return result.response();
    }

    private DocumentVersionUploadTransactionResult persist(
        DocumentVersionUploadCommand command, VersionUploadRequestService.Claim claim
    ) {
        return claim == null ? documentVersionUploadService.upload(command)
            : documentVersionUploadService.upload(command, claim);
    }

    /** 만료된 후보를 확인해 재사용하고, 첫 시도라면 위치를 DB에 기록한 뒤 저장한다. */
    private StoredFile recoverOrStoreCandidate(
        VersionUploadRequestService.Claim claim, DocumentVersionUploadRequest request,
        ValidatedFile file, String fileHash
    ) {
        VersionUploadRequestService.Candidate previous = claim.candidate();
        if (previous != null) {
            if (!fileHash.equals(previous.fileHash()) || file.fileSize() != previous.fileSize()) {
                throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_CANDIDATE_MISMATCH);
            }
            if (inspectMatchingCandidate(claim, previous.storedFile(), fileHash, file.fileSize())) {
                return previous.storedFile();
            }
            // 이전 소유자의 지연 저장과 겹쳐도 추적되지 않는 두 번째 키를 만들지 않는다.
            try {
                StoredFile stored = storeCandidate(request, file, previous.storedFile().objectKey());
                if (!stored.equals(previous.storedFile())) {
                    throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
                }
            } catch (DocGridException exception) {
                if (!inspectMatchingCandidate(claim, previous.storedFile(), fileHash, file.fileSize())) {
                    throw exception;
                }
            }
            fileStorageService.candidateGeneration(previous.storedFile()).ifPresent(
                generation -> versionUploadRequestService.recordCandidateGeneration(
                    claim, previous.storedFile(), generation
                )
            );
            return previous.storedFile();
        }

        StoredFile planned = new StoredFile(
            StorageProvider.valueOf(fileStorageProperties.getType().name()),
            fileStorageProperties.getBucket(), newObjectKey(file)
        );
        // 첫 후보의 DB 기록이 성공해야 외부 저장소에 원본을 쓴다.
        versionUploadRequestService.registerCandidate(claim, planned, fileHash, file.fileSize());
        StoredFile stored = storeCandidate(request, file, planned.objectKey());
        if (!stored.equals(planned)) {
            throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
        }
        fileStorageService.candidateGeneration(stored).ifPresent(
            generation -> versionUploadRequestService.recordCandidateGeneration(claim, stored, generation)
        );
        return stored;
    }

    private boolean inspectMatchingCandidate(
        VersionUploadRequestService.Claim claim, StoredFile candidate, String fileHash, long fileSize
    ) {
        Optional<CandidateObjectSnapshot> snapshot = fileStorageService.inspectCandidate(candidate);
        if (snapshot.isEmpty()) {
            return false;
        }
        CandidateObjectSnapshot actual = snapshot.get();
        if (!fileHash.equals(actual.sha256()) || fileSize != actual.fileSize()) {
            throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_CANDIDATE_MISMATCH);
        }
        if (actual.generation() != null) {
            versionUploadRequestService.recordCandidateGeneration(claim, candidate, actual.generation());
        }
        return true;
    }

    /** 검증된 새 버전 파일을 지정된 객체 키로 저장한다. */
    private StoredFile storeCandidate(
        DocumentVersionUploadRequest request, ValidatedFile validatedFile, String objectKey
    ) {
        try (InputStream inputStream = request.file().getInputStream()) {
            return fileStorageService.store(
                inputStream, validatedFile.fileSize(), validatedFile.contentType(), objectKey
            );
        } catch (IOException exception) {
            log.error("파일 저장소 업로드용 원본 Stream을 열지 못했습니다.", exception);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, exception);
        }
    }

    private String newObjectKey(ValidatedFile file) {
        return "documents/%s/%s.%s".formatted(UUID.randomUUID(), UUID.randomUUID(), file.extension());
    }

    /**
     * Facade에서 준비한 값들을 DB 트랜잭션에 전달할 명령 객체로 조립한다.
     */
    private DocumentVersionUploadCommand createCommand(
        Long userId,
        Long documentId,
        ValidatedFile validatedFile,
        String fileHash,
        Long existingFileObjectId,
        StoredFile storedFile
    ) {
        return new DocumentVersionUploadCommand(
            userId, documentId, validatedFile, fileHash, existingFileObjectId, storedFile
        );
    }

    /**
     * DB에서 점유하지 않은 저장소 후보를 최선 노력 방식으로 삭제한다.
     *
     * <p>정리 실패는 로그로 남기되 성공한 DB 결과나 원래 예외를 바꾸지 않는다.
     */
    private void cleanupCandidate(StoredFile candidate) {
        try {
            fileStorageService.delete(candidate);
        } catch (RuntimeException cleanupException) {
            log.error("사용되지 않은 저장소 Object 정리에 실패했습니다.", cleanupException);
        }
    }
}
