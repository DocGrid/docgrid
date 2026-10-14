package com.opensource.docgrid.domain.document.service.command;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.ValidatedFile;
import com.opensource.docgrid.domain.document.service.VersionUploadRequestService;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 기존 문서에 새 파일 버전을 추가하고 해당 버전의 최초 인덱싱 Job을 생성한다.
 *
 * <p>쓰기 권한, 문서 상태, 문서 형식, 동일 내용, 진행 중 버전 존재 여부를 검증하고
 * {@link DocumentVersion}, 동기화 Outbox Event, {@link EmbeddingJob}과 멱등 키의 완료 응답을
 * 한 트랜잭션에 저장한다.
 * 문서별 진행 중 버전은 하나만 허용하며, 애플리케이션 검증 이후 발생할 수 있는 동시 요청은
 * 데이터베이스 유일 제약까지 해석해 동일한 도메인 오류로 변환한다.
 *
 * <p>실제 파일 바이너리 저장과 사용되지 않은 후보 파일 정리는
 * {@code DocumentVersionUploadFacade}의 책임이며, 이 클래스는 데이터베이스 상태만 확정한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentVersionUploadService {

    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;
    private static final String IN_PROGRESS_CONSTRAINT = "uk_document_versions_one_in_progress";
    private static final Set<DocumentVersionStatus> IN_PROGRESS_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );

    private final UserRepository userRepository;
    private final FileObjectResolutionService fileObjectResolutionService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final PermissionQueryService permissionQueryService;
    private final SyncEventWriter syncEventWriter;
    private final VersionUploadRequestService versionUploadRequestService;

    /**
     * 새 버전 파일을 저장하기 전에 권한과 버전 생성 가능 여부를 검증하고 중복 파일을 조회한다.
     *
     * <p>Facade가 불필요한 파일 저장소 업로드를 피할 수 있도록 읽기 전용 단계로 분리되어 있다.
     * 실제 업로드 시점에는 상태가 달라질 수 있으므로 {@link #upload(DocumentVersionUploadCommand)}에서
     * 문서를 잠근 뒤 같은 조건을 다시 검증한다.
     *
     * @param userId 새 버전을 등록하려는 사용자 ID
     * @param documentId 대상 문서 ID
     * @param file 형식과 크기 검증이 끝난 파일 정보
     * @param fileHash 원본 파일의 SHA-256 해시
     * @return 현재 저장소에서 재사용 가능한 FileObject ID, 없으면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<Long> prepare(Long userId, Long documentId, ValidatedFile file, String fileHash) {
        // 1. 파일 저장 전에 권한과 대상 문서의 존재 여부를 확인한다.
        validateWritePermission(userId, documentId);
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

        // 2. 새 버전을 받을 수 있는 상태와 파일인지 확인한다.
        validate(document, file, fileHash);

        // 3. 같은 바이너리가 있으면 저장소 업로드 없이 기존 FileObject를 재사용하도록 ID를 반환한다.
        return fileObjectResolutionService.findReusableFileObjectId(fileHash, file.fileSize());
    }

    /**
     * 기존 문서의 새 버전과 후속 인덱싱 의도를 하나의 트랜잭션으로 저장한다.
     *
     * <p>문서 행 잠금과 진행 중 버전 유일 제약을 함께 사용해 동시 업로드를 직렬화한다.
     * 반환값의 후보 점유 여부는 Facade가 트랜잭션 밖에서 저장소 객체를 정리할 때 사용한다.
     *
     * @param command 대상 문서, 사용자, 검증된 파일과 저장소 후보를 포함한 명령
     * @return 생성된 버전·Job 정보와 후보 파일 점유 결과
     */
    public DocumentVersionUploadTransactionResult upload(DocumentVersionUploadCommand command) {
        return upload(command, null);
    }

    /** 요청 소유권이 있으면 버전·Job 생성과 최초 응답 확정을 함께 커밋한다. */
    public DocumentVersionUploadTransactionResult upload(
        DocumentVersionUploadCommand command, VersionUploadRequestService.Claim claim
    ) {
        // 1. 사용자와 쓰기 권한을 확인하고 문서 행을 잠가 버전 번호 및 상태 결정을 직렬화한다.
        userRepository.findById(command.userId())
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
        validateWritePermission(command.userId(), command.documentId());
        Document document = documentRepository.findByIdForUpdate(command.documentId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));
        validate(document, command.validatedFile(), command.fileHash());

        // 2. 기존 FileObject 재사용 또는 새 후보 점유를 확정하고 다음 버전 번호를 계산한다.
        FileObjectResolutionService.Resolution resolution = fileObjectResolutionService.resolve(
            command.userId(), command.validatedFile(), command.fileHash(),
            command.existingFileObjectId(), command.storedFile()
        );
        User user = userRepository.getReferenceById(command.userId());
        int versionNo = documentVersionRepository.findMaxVersionNo(document.getId()) + 1;

        // 3. 새 버전을 즉시 Flush해 진행 중 버전 유일 제약 위반을 이 메서드 안에서 도메인 오류로 변환한다.
        DocumentVersion version;
        try {
            version = documentVersionRepository.saveAndFlush(
                DocumentVersion.builder()
                    .document(document)
                    .fileObject(resolution.fileObject())
                    .versionNo(versionNo)
                    .titleSnapshot(document.getTitle())
                    .fileHash(command.fileHash())
                    .originalFilename(command.validatedFile().originalFilename())
                    .contentType(command.validatedFile().contentType())
                    .status(DocumentVersionStatus.UPLOADED)
                    .createdBy(user)
                    .build()
            );
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, IN_PROGRESS_CONSTRAINT)) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS, exception);
            }
            throw exception;
        }

        // 4. 이전 인덱싱 실패 뒤 재업로드하는 경우 문서도 다시 처리 가능한 UPLOADED 상태로 복구한다.
        if (document.getStatus() == DocumentStatus.FAILED) {
            document.markUploaded();
        }

        // 5. Outbox Event와 최초 Job이 동일한 버전·모델을 가리키도록 한 트랜잭션에 함께 기록한다.
        EmbeddingModel embeddingModel = embeddingModelQueryService.getActiveModel();
        SyncOutboxEvent sourceEvent = syncEventWriter.recordDocumentVersionCreated(version, embeddingModel);
        EmbeddingJob job = embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(version)
                .embeddingModel(embeddingModel)
                .sourceEventId(sourceEvent.getEventId())
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );

        // 6. 현재 공개 버전은 인덱싱 완료 전까지 유지하고, 새 처리 버전과 Job 상태를 응답으로 제공한다.
        DocumentVersionUploadResponse response = new DocumentVersionUploadResponse(
            document.getId(),
            version.getId(),
            versionNo,
            job.getId(),
            document.getCurrentVersion() == null ? null : document.getCurrentVersion().getId(),
            document.getStatus(),
            version.getStatus(),
            job.getStatus()
        );
        if (claim != null) {
            // 7. 리스 소유권을 잃었다면 버전·Outbox·Job 전체를 롤백한다.
            versionUploadRequestService.complete(claim, response, resolution.candidateClaimed());
        }
        return new DocumentVersionUploadTransactionResult(response, resolution.candidateClaimed());
    }

    /**
     * 사용자가 대상 문서에 새 버전을 추가할 WRITE 권한을 가졌는지 확인한다.
     *
     * @param userId 권한을 확인할 사용자 ID
     * @param documentId 대상 문서 ID
     */
    private void validateWritePermission(Long userId, Long documentId) {
        // UI의 canWrite와 같은 권한 계산을 사용해 WRITE 보유자가 실제 새 버전 업로드도 수행할 수 있게 한다.
        if (!permissionQueryService.canWriteDocument(userId, documentId)) {
            throw new DocGridException(ErrorCode.PERMISSION_DENIED);
        }
    }

    /**
     * 문서가 새 버전을 받을 수 있고 업로드 파일이 기존 문서 규칙과 호환되는지 검증한다.
     *
     * <p>INDEXED 문서는 현재 공개 버전과, FAILED 문서는 가장 최근 실패 버전과 비교한다.
     * 처리 중인 문서나 동일 바이너리 업로드는 새 인덱싱 작업을 만들지 않도록 거부한다.
     *
     * @param document 잠금 또는 조회가 끝난 대상 문서
     * @param file 형식과 크기 검증이 끝난 파일 정보
     * @param fileHash 원본 파일의 SHA-256 해시
     */
    private void validate(Document document, ValidatedFile file, String fileHash) {
        // 1. 삭제 문서는 다른 API와 동일하게 존재하지 않는 것으로 숨기고 업로드 원본 문서만 허용한다.
        if (document.getStatus() == DocumentStatus.DELETED) {
            throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        if (document.getSourceType() != DocumentSourceType.UPLOAD) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
        }
        if (document.getDocumentType() != file.documentType()) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_TYPE_MISMATCH);
        }

        // 2. 아직 인덱싱 중인 버전이 있으면 문서별 단일 처리 버전 불변식을 위해 새 요청을 거부한다.
        if (documentVersionRepository.existsByDocumentIdAndStatusIn(document.getId(), IN_PROGRESS_STATUSES)) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_IN_PROGRESS);
        }

        // 3. 성공 문서는 현재 공개 버전, 실패 문서는 마지막 실패 버전을 새 파일 비교 기준으로 선택한다.
        DocumentVersion comparisonVersion;
        if (document.getStatus() == DocumentStatus.INDEXED) {
            comparisonVersion = document.getCurrentVersion();
            if (comparisonVersion == null || comparisonVersion.getStatus() != DocumentVersionStatus.INDEXED) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
            }
        } else if (document.getStatus() == DocumentStatus.FAILED) {
            comparisonVersion = documentVersionRepository.findTopByDocumentIdOrderByVersionNoDesc(document.getId())
                .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED));
            if (comparisonVersion.getStatus() != DocumentVersionStatus.FAILED) {
                throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
            }
        } else {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_NOT_ALLOWED);
        }

        // 4. 해시와 크기가 모두 같으면 내용 변경이 없으므로 중복 버전과 인덱싱 Job을 만들지 않는다.
        if (isSameFile(comparisonVersion, fileHash, file.fileSize())) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_SAME_CONTENT);
        }
    }

    /**
     * 파일 해시와 크기를 함께 비교해 동일 바이너리인지 판단한다.
     */
    private boolean isSameFile(DocumentVersion version, String fileHash, long fileSize) {
        return fileHash.equals(version.getFileHash())
            && version.getFileObject() != null
            && version.getFileObject().getFileSize() == fileSize;
    }

    /**
     * JDBC 예외 원인 체인을 따라가며 지정한 데이터베이스 제약 이름이 포함됐는지 확인한다.
     *
     * <p>Driver별로 제약 위반이 중첩 예외의 서로 다른 위치에 담길 수 있으므로 전체 원인을 순회한다.
     */
    private boolean containsConstraint(Throwable throwable, String constraint) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
