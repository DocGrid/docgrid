package com.opensource.docgrid.domain.document.service.command;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentSourceType;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 최초 문서 업로드에 필요한 데이터베이스 변경을 하나의 트랜잭션으로 처리한다.
 *
 * <p>사용자와 파일 객체를 확인한 뒤 {@link Document}, 최초 {@link DocumentVersion},
 * 동기화 Outbox Event, 최초 {@link EmbeddingJob}과 멱등 키의 완료 응답을 함께 저장한다. 이 서비스의 책임은
 * 업로드 메타데이터와 인덱싱 의도를 원자적으로 기록하는 데 있으며, 실제 파일 바이너리 저장과
 * 실패한 후보 파일 정리는 {@code DocumentUploadFacade}가 담당한다.
 *
 * <p>트랜잭션이 실패하면 문서·버전·Outbox·Job이 모두 Rollback되어 일부 데이터만 남지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentUploadService {

    private static final int INITIAL_VERSION = 1;
    private static final int DEFAULT_JOB_PRIORITY = 0;
    private static final int MAX_RETRY_COUNT = 3;

    private final UserRepository userRepository;
    private final FileObjectResolutionService fileObjectResolutionService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingModelQueryService embeddingModelQueryService;
    private final SyncEventWriter syncEventWriter;
    private final InitialUploadRequestService initialUploadRequestService;

    /**
     * 같은 해시와 크기를 가진 파일 객체가 현재 저장소에서 재사용 가능한지 조회한다.
     *
     * <p>파일 바이너리를 새로 저장하기 전에 호출되며, 반환된 ID는 실제 업로드 트랜잭션에서
     * 다시 검증한다. 사전 조회와 저장 사이의 동시성 때문에 이 결과만으로 재사용을 확정하지 않는다.
     *
     * @param fileHash 원본 파일의 SHA-256 해시
     * @param fileSize 원본 파일 크기(Byte)
     * @return 재사용 가능한 FileObject ID, 없으면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<Long> findReusableFileObjectId(String fileHash, long fileSize) {
        return fileObjectResolutionService.findReusableFileObjectId(fileHash, fileSize);
    }

    /**
     * 문서 최초 업로드의 메타데이터와 인덱싱 작업을 원자적으로 생성한다.
     *
     * <p>파일 객체는 기존 객체를 재사용하거나 Facade가 미리 저장한 후보를 점유한다. 반환값에는
     * API 응답과 함께 후보 파일의 점유 여부를 담아, 트랜잭션 밖의 Facade가 불필요한 파일을 정리할 수 있게 한다.
     *
     * @param command 검증된 파일 정보와 저장소 후보를 포함한 업로드 명령
     * @return 생성된 문서·버전·Job 정보와 후보 파일 점유 결과
     */
    public DocumentUploadTransactionResult upload(DocumentUploadCommand command) {
        return upload(command, null);
    }

    /** 멱등 요청 소유권이 있으면 최초 업로드 결과를 같은 DB 트랜잭션에 확정한다. */
    public DocumentUploadTransactionResult upload(
        DocumentUploadCommand command, InitialUploadRequestService.Claim claim
    ) {
        // 1. 존재하는 사용자만 문서를 소유할 수 있도록 확인한 뒤 파일 중복 경쟁 결과를 확정한다.
        userRepository.findById(command.userId())
            .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));

        FileObjectResolutionService.Resolution resolution = fileObjectResolutionService.resolve(
            command.userId(), command.validatedFile(), command.fileHash(),
            command.existingFileObjectId(), command.storedFile()
        );
        User user = userRepository.getReferenceById(command.userId());

        // 2. 사용자가 조회·관리할 문서 원장을 UPLOADED 상태로 생성한다.
        Document document = documentRepository.save(
            Document.builder()
                .owner(user)
                .title(command.title())
                .description(command.description())
                .documentType(command.validatedFile().documentType())
                .sourceType(DocumentSourceType.UPLOAD)
                .status(DocumentStatus.UPLOADED)
                .visibility(command.visibility())
                .build()
        );

        // 3. 실제 파일과 인덱싱 상태를 추적할 최초 버전을 만들고 문서의 현재 버전으로 연결한다.
        DocumentVersion documentVersion = documentVersionRepository.save(
            DocumentVersion.builder()
                .document(document)
                .fileObject(resolution.fileObject())
                .versionNo(INITIAL_VERSION)
                .titleSnapshot(command.title())
                .fileHash(command.fileHash())
                .originalFilename(command.validatedFile().originalFilename())
                .contentType(command.validatedFile().contentType())
                .status(DocumentVersionStatus.UPLOADED)
                .createdBy(user)
                .build()
        );
        document.updateCurrentVersion(documentVersion);

        // 4. Outbox Event와 최초 Job이 항상 같은 임베딩 모델을 바라보도록 활성 모델을 한 번만 조회한다.
        EmbeddingModel embeddingModel = embeddingModelQueryService.getActiveModel();

        // 5. 버전 생성 Event와 즉시 처리할 Job을 함께 기록해 정상 처리와 장애 복구가 같은 근거를 사용하게 한다.
        SyncOutboxEvent sourceEvent = syncEventWriter.recordDocumentVersionCreated(documentVersion, embeddingModel);
        EmbeddingJob embeddingJob = embeddingJobRepository.save(
            EmbeddingJob.builder()
                .documentVersion(documentVersion)
                .embeddingModel(embeddingModel)
                .sourceEventId(sourceEvent.getEventId())
                .status(EmbeddingJobStatus.PENDING)
                .priority(DEFAULT_JOB_PRIORITY)
                .maxRetryCount(MAX_RETRY_COUNT)
                .build()
        );

        // 6. 소유권이 이미 교체됐다면 전체 업로드를 롤백하고, 아니면 원본 응답을 함께 커밋한다.
        DocumentUploadResponse response = new DocumentUploadResponse(
            document.getId(),
            documentVersion.getId(),
            resolution.fileObject().getId(),
            embeddingJob.getId(),
            document.getStatus(),
            embeddingJob.getStatus()
        );
        if (claim != null) {
            initialUploadRequestService.complete(claim, response);
        }

        // 7. 커밋 후 외부 계층이 필요한 식별자와 후보 파일 정리 판단을 함께 반환한다.
        return new DocumentUploadTransactionResult(response, resolution.candidateClaimed());
    }

}
