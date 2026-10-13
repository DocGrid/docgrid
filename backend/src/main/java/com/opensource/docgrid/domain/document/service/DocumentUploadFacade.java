package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadCommand;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadService;
import com.opensource.docgrid.domain.document.service.command.DocumentUploadTransactionResult;
import com.opensource.docgrid.domain.document.storage.CandidateObjectSnapshot;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 최초 문서 업로드의 파일 저장소 작업과 데이터베이스 트랜잭션을 조율한다.
 *
 * <p>파일 검증·해시 계산·중복 조회 후 필요할 때만 바이너리를 저장하고,
 * {@link DocumentUploadService}에 데이터베이스 변경을 위임한다. 파일 저장소는 DB 트랜잭션에
 * 참여하지 않으므로 키 없는 기존 요청은 DB 실패 시 후보를 보상 삭제한다. 멱등 키가 있는 요청은
 * 저장 전에 후보 위치를 원장에 기록하고, 확인된 저장 실패나 앱 중단 뒤 같은 후보를 검사해 재사용한다.
 * 이 클래스는 업로드 순서와 보상 동작을 책임지며 문서 Entity를 직접 저장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadFacade {

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentUploadService documentUploadService;
    private final InitialUploadRequestService initialUploadRequestService;
    private final FileStorageProperties fileStorageProperties;

    /**
     * 업로드 파일을 검증하고 저장소와 데이터베이스에 최초 문서를 생성한다.
     *
     * @param userId 문서를 소유할 사용자 ID
     * @param request 파일과 문서 메타데이터를 담은 요청
     * @return 생성된 문서·버전·인덱싱 Job 정보
     */
    public DocumentUploadResponse upload(Long userId, DocumentUploadRequest request) {
        return upload(userId, request, null);
    }

    /**
     * 선택적 멱등 키의 처리 소유권을 확보한 뒤 최초 문서를 생성하거나 확정된 원본 응답을 재사용한다.
     */
    public DocumentUploadResponse upload(Long userId, DocumentUploadRequest request, String idempotencyKey) {
        // 1. 저장 전에 파일 형식·크기·이름을 검증하고 동일 바이너리 판별용 해시를 계산한다.
        ValidatedFile validatedFile = fileValidationService.validate(request.file());
        String fileHash = fileHashService.calculateSha256(request.file());
        InitialUploadRequestService.Claim claim = null;
        if (idempotencyKey != null) {
            // 2. 완료 응답은 즉시 반환하며, 진행 중이거나 다른 입력인 키는 저장소 작업 전에 거부한다.
            String fingerprint = initialUploadRequestService.fingerprint(request, validatedFile, fileHash);
            claim = initialUploadRequestService.acquire(userId, idempotencyKey, fingerprint);
            if (claim.completedResponse() != null) {
                return claim.completedResponse();
            }
        }

        // 3. DB와 저장소 처리 중 확인된 실패는 현재 소유권을 풀어 같은 입력의 재시도를 허용한다.
        try {
            return uploadClaimed(userId, request, validatedFile, fileHash, claim);
        } catch (RuntimeException e) {
            if (claim != null) {
                try {
                    initialUploadRequestService.release(claim);
                } catch (RuntimeException releaseException) {
                    e.addSuppressed(releaseException);
                }
            }
            throw e;
        }
    }

    private DocumentUploadResponse uploadClaimed(
        Long userId, DocumentUploadRequest request, ValidatedFile validatedFile, String fileHash,
        InitialUploadRequestService.Claim claim
    ) {
        Optional<Long> existingFileObjectId = documentUploadService.findReusableFileObjectId(
            fileHash, validatedFile.fileSize()
        );

        // 1. 같은 바이너리의 FileObject가 있으면 물리 파일을 다시 저장하지 않고 바로 DB Transaction을 실행한다.
        if (existingFileObjectId.isPresent()) {
            return persist(createCommand(
                userId, request, validatedFile, fileHash, existingFileObjectId.get(), null
            ), claim).response();
        }

        // 2. 키가 있는 요청은 기존 후보의 실제 바이트를 검증하거나 새 후보 위치를 먼저 DB에 기록한다.
        StoredFile candidate = claim == null
            ? storeCandidate(request, validatedFile, newObjectKey(validatedFile))
            : recoverOrStoreCandidate(claim, request, validatedFile, fileHash);
        DocumentUploadTransactionResult result;

        // 3. 키가 있는 후보는 실패 후 재시도에 필요하므로 즉시 삭제하지 않는다.
        try {
            result = persist(createCommand(
                userId, request, validatedFile, fileHash, null, candidate
            ), claim);
        } catch (RuntimeException e) {
            if (claim == null) {
                cleanupCandidate(candidate);
            }
            throw e;
        }

        // 4. 동시 업로드가 같은 FileObject를 먼저 만들었다면 이번 요청의 미사용 후보만 정리한다.
        if (!result.candidateClaimed() && claim == null) {
            cleanupCandidate(candidate);
        }
        return result.response();
    }

    private DocumentUploadTransactionResult persist(
        DocumentUploadCommand command, InitialUploadRequestService.Claim claim
    ) {
        return claim == null ? documentUploadService.upload(command) : documentUploadService.upload(command, claim);
    }

    /** 만료된 시도의 후보가 있으면 내용과 generation을 검사하고, 없으면 위치를 먼저 등록해 저장한다. */
    private StoredFile recoverOrStoreCandidate(
        InitialUploadRequestService.Claim claim, DocumentUploadRequest request,
        ValidatedFile file, String fileHash
    ) {
        InitialUploadRequestService.Candidate previous = claim.candidate();
        if (previous != null) {
            if (!fileHash.equals(previous.fileHash()) || file.fileSize() != previous.fileSize()) {
                throw new DocGridException(ErrorCode.DOCUMENT_UPLOAD_CANDIDATE_MISMATCH);
            }
            if (inspectMatchingCandidate(claim, previous.storedFile(), fileHash, file.fileSize())) {
                return previous.storedFile();
            }
            // 이전 소유자의 쓰기가 늦게 끝나도 추적되지 않는 두 번째 객체를 만들지 않는다.
            try {
                StoredFile stored = storeCandidate(request, file, previous.storedFile().objectKey());
                if (!stored.equals(previous.storedFile())) {
                    throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
                }
            } catch (DocGridException exception) {
                // GCS doesNotExist 경합에서는 다른 쓰기가 먼저 끝났을 수 있으므로 원본을 다시 검증한다.
                if (!inspectMatchingCandidate(claim, previous.storedFile(), fileHash, file.fileSize())) {
                    throw exception;
                }
            }
            fileStorageService.candidateGeneration(previous.storedFile()).ifPresent(
                generation -> initialUploadRequestService.recordCandidateGeneration(
                    claim, previous.storedFile(), generation
                )
            );
            return previous.storedFile();
        }

        // 첫 후보의 DB 기록이 성공해야 외부 저장소 호출을 시작한다.
        StoredFile planned = new StoredFile(
            StorageProvider.valueOf(fileStorageProperties.getType().name()),
            fileStorageProperties.getBucket(), newObjectKey(file)
        );
        initialUploadRequestService.registerCandidate(claim, planned, fileHash, file.fileSize());
        StoredFile stored = storeCandidate(request, file, planned.objectKey());
        if (!stored.equals(planned)) {
            throw new DocGridException(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH);
        }
        fileStorageService.candidateGeneration(stored).ifPresent(
            generation -> initialUploadRequestService.recordCandidateGeneration(claim, stored, generation)
        );
        return stored;
    }

    private boolean inspectMatchingCandidate(
        InitialUploadRequestService.Claim claim, StoredFile candidate, String fileHash, long fileSize
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
            initialUploadRequestService.recordCandidateGeneration(claim, candidate, actual.generation());
        }
        return true;
    }

    /**
     * 검증된 파일을 충돌 가능성이 낮은 임의 객체 키로 파일 저장소에 기록한다.
     */
    private StoredFile storeCandidate(
        DocumentUploadRequest request, ValidatedFile validatedFile, String objectKey
    ) {
        try (InputStream inputStream = request.file().getInputStream()) {
            return fileStorageService.store(
                inputStream,
                validatedFile.fileSize(),
                validatedFile.contentType(),
                objectKey
            );
        } catch (IOException e) {
            log.error("파일 저장소 업로드용 원본 Stream을 열지 못했습니다.", e);
            throw new DocGridException(ErrorCode.FILE_STORAGE_FAILED, e);
        }
    }

    private String newObjectKey(ValidatedFile file) {
        return "documents/%s/%s.%s".formatted(UUID.randomUUID(), UUID.randomUUID(), file.extension());
    }

    /**
     * Facade 단계의 값들을 DB 트랜잭션에 전달할 불변 명령 객체로 조립한다.
     */
    private DocumentUploadCommand createCommand(
        Long userId,
        DocumentUploadRequest request,
        ValidatedFile validatedFile,
        String fileHash,
        Long existingFileObjectId,
        StoredFile storedFile
    ) {
        return new DocumentUploadCommand(
            userId,
            request.title(),
            request.description(),
            request.visibility(),
            validatedFile,
            fileHash,
            existingFileObjectId,
            storedFile
        );
    }

    /**
     * DB Row에 연결되지 않은 저장소 후보를 최선 노력 방식으로 삭제한다.
     *
     * <p>정리 실패가 원래 업로드 결과나 예외를 덮지 않도록 기록만 남긴다.
     */
    private void cleanupCandidate(StoredFile candidate) {
        try {
            fileStorageService.delete(candidate);
        } catch (RuntimeException cleanupException) {
            log.error("사용되지 않은 저장소 Object 정리에 실패했습니다.", cleanupException);
        }
    }
}
