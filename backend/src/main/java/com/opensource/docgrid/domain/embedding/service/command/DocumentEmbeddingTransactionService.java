package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.embedding.entity.Embedding;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingDraft;
import com.opensource.docgrid.domain.embedding.service.EmbeddingVectorSupport;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.entity.IndexingEvent;
import com.opensource.docgrid.domain.worker.enums.AttemptStatus;
import com.opensource.docgrid.domain.worker.enums.IndexingEventType;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.domain.worker.repository.IndexingEventRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 문서 Chunk Embedding 생성의 준비와 완료 단계를 짧은 DB Transaction으로 분리해 수행한다.
 *
 * <p>두 단계는 Job을 먼저, Version을 다음 순서로 잠가 Claim 교체와 같은 Version의 동시 실행을
 * 직렬화한다. 준비 단계는 Job에 고정된 Model과 Chunk 불변 Snapshot만 외부 호출 구간에 전달한다.
 * Reconciler 복구 Job은 기존 Vector를 보존하고 같은 Version·Model에서 실제 누락된 Chunk만 채운다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentEmbeddingTransactionService {

    private static final String EMBEDDING_STARTED_MESSAGE = "Document Version Embedding 생성을 시작했습니다.";

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IndexingEventRepository indexingEventRepository;
    private final EmbeddingJobOwnershipValidator ownershipValidator;
    private final Clock clock;

    /**
     * 외부 Embedding 호출 전에 실행 소유권과 저장 상태를 검증하고 불변 작업 Snapshot을 만든다.
     */
    public PreparationResult prepare(
        Long jobId,
        Long attemptId,
        Long workerId,
        String claimToken
    ) {
        // 1. Claim 교체와 같은 Job의 중복 요청을 직렬화하도록 Job을 먼저 잠근다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        LocalDateTime preparedAt = LocalDateTime.now(clock);
        ownershipValidator.validate(embeddingJob, workerId, claimToken, preparedAt);
        validateAttempt(embeddingJob, attemptId, workerId, claimToken);

        // 2. Job이 직접 가리키는 Version과 Model을 고정하고 Version 행을 잠근다.
        EmbeddingModel embeddingModel = findJobModel(embeddingJob);
        DocumentVersion documentVersion = findLockedJobVersion(embeddingJob);

        // 3. Chunk Set 전체를 순서대로 검증해 부분·중복·누락된 입력을 외부 호출 전에 차단한다.
        List<DocumentChunk> chunks = documentChunkRepository
            .findAllByDocumentVersionIdOrderByChunkIndexAsc(documentVersion.getId());
        validateChunks(documentVersion, chunks);

        // 4. Version 상태와 현재 Model의 저장 개수를 함께 비교해 작업·재개·완료 재생을 구분한다.
        EmbeddingState state = resolveState(documentVersion, embeddingModel, chunks.size());
        if (state == EmbeddingState.REPLAY) {
            return PreparationResult.replay(result(
                jobId,
                attemptId,
                documentVersion,
                embeddingModel,
                chunks.size(),
                false
            ));
        }

        // 5. 최초 CHUNKED 요청만 EMBEDDING 상태와 시작 이벤트를 같은 Transaction에 기록한다.
        if (documentVersion.getStatus() == DocumentVersionStatus.CHUNKED) {
            documentVersion.markEmbedding();
            indexingEventRepository.save(IndexingEvent.builder()
                .embeddingJob(embeddingJob)
                .eventType(IndexingEventType.EMBEDDING_STARTED)
                .fromStatus(DocumentVersionStatus.CHUNKED.name())
                .toStatus(DocumentVersionStatus.EMBEDDING.name())
                .message(EMBEDDING_STARTED_MESSAGE)
                .occurredAt(preparedAt)
                .build());
        }

        return PreparationResult.work(new EmbeddingWork(
            documentVersion.getId(),
            embeddingModel.getId(),
            embeddingModel.getModelName(),
            embeddingModel.getDimension(),
            chunks.stream()
                .map(chunk -> new ChunkSnapshot(
                    chunk.getId(),
                    chunk.getChunkIndex(),
                    chunk.getChunkText(),
                    SectionPath.embeddingInput(chunk.getMetadataJson(), chunk.getChunkText()),
                    chunk.getTokenCount(),
                    chunk.getContentHash()
                ))
                .toList()
        ));
    }

    /**
     * 외부 호출 결과를 현재 실행 소유권과 준비 Snapshot으로 재검증한 뒤 한 번에 저장한다.
     */
    public CompletionResult complete(
        Long jobId,
        Long attemptId,
        Long workerId,
        String claimToken,
        EmbeddingWork preparedWork,
        List<DocumentEmbeddingDraft> drafts
    ) {
        // 1. 외부 호출 중 Claim 교체나 Lease 만료를 차단하도록 Job과 Attempt를 다시 검증한다.
        EmbeddingJob embeddingJob = findLockedJob(jobId);
        LocalDateTime completedAt = LocalDateTime.now(clock);
        ownershipValidator.validate(embeddingJob, workerId, claimToken, completedAt);
        validateAttempt(embeddingJob, attemptId, workerId, claimToken);

        // 2. Job 고정 Model과 Version을 다시 확인하고 준비 단계와 같은 대상인지 검증한다.
        EmbeddingModel embeddingModel = findJobModel(embeddingJob);
        DocumentVersion documentVersion = findLockedJobVersion(embeddingJob);
        validatePreparedTarget(preparedWork, documentVersion, embeddingModel);

        // 3. 현재 Chunk Set을 다시 읽어 외부 호출 중 원본이 바뀌거나 누락되지 않았는지 확인한다.
        List<DocumentChunk> chunks = documentChunkRepository
            .findAllByDocumentVersionIdOrderByChunkIndexAsc(documentVersion.getId());
        validateChunks(documentVersion, chunks);
        validatePreparedChunks(preparedWork, chunks);

        // 4. 동시 요청이 먼저 전체 저장했으면 재생하고 Reconciler 복구의 부분 Set은 누락분만 채운다.
        EmbeddingState state = resolveState(documentVersion, embeddingModel, chunks.size());
        if (state == EmbeddingState.REPLAY) {
            return result(jobId, attemptId, documentVersion, embeddingModel, chunks.size(), false);
        }
        if (documentVersion.getStatus() != DocumentVersionStatus.EMBEDDING) {
            throw new DocGridException(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED);
        }

        // 5. 모든 Draft를 다시 검증하고 아직 없는 Chunk의 ACTIVE Embedding만 원자 저장한다.
        List<Embedding> embeddings = toEntities(
            documentVersion,
            embeddingModel,
            chunks,
            drafts
        );
        if (!embeddings.isEmpty()) {
            embeddingRepository.saveAllAndFlush(embeddings);
        }

        return result(jobId, attemptId, documentVersion, embeddingModel, chunks.size(), true);
    }

    /**
     * Job 행을 비관적 잠금으로 조회해 같은 인덱싱 작업의 저장 단계를 직렬화한다.
     */
    private EmbeddingJob findLockedJob(Long jobId) {
        return embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));
    }

    /**
     * Job에 고정된 문서 버전을 잠금 조회한다.
     *
     * <p>재인덱싱 중 문서의 currentVersion은 이전 공개 버전일 수 있으므로 Job의 직접 참조만 사용한다.
     */
    private DocumentVersion findLockedJobVersion(EmbeddingJob embeddingJob) {
        if (embeddingJob.getDocumentVersion() == null
            || embeddingJob.getDocumentVersion().getId() == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        return documentVersionRepository.findByIdForUpdate(embeddingJob.getDocumentVersion().getId())
            .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));
    }

    /**
     * Job에 고정된 임베딩 모델이 외부 호출과 Vector 저장에 필요한 정보를 갖췄는지 확인한다.
     */
    private EmbeddingModel findJobModel(EmbeddingJob embeddingJob) {
        EmbeddingModel embeddingModel = embeddingJob.getEmbeddingModel();
        if (embeddingModel == null
            || embeddingModel.getId() == null
            || !StringUtils.hasText(embeddingModel.getModelName())
            || embeddingModel.getDimension() <= 0) {
            throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
        }
        return embeddingModel;
    }

    /**
     * 요청 Attempt가 현재 Claim Token으로 시작됐고 같은 Worker에 속하는지 검증한다.
     *
     * <p>Lease가 교체된 뒤 이전 Worker가 늦게 반환한 Vector를 저장하지 못하도록 Attempt의
     * ID, 상태, Worker 소유권을 모두 확인한다.
     */
    private void validateAttempt(
        EmbeddingJob embeddingJob,
        Long attemptId,
        Long workerId,
        String claimToken
    ) {
        EmbeddingJobAttempt attempt = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(embeddingJob.getId(), claimToken)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID));

        if (!Objects.equals(attempt.getId(), attemptId)
            || attempt.getStatus() != AttemptStatus.STARTED
            || attempt.getWorkerNode() == null
            || !Objects.equals(attempt.getWorkerNode().getId(), workerId)) {
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_ATTEMPT_INVALID);
        }
    }

    /**
     * 문서 버전의 Chunk Set이 비어 있지 않고 ID·순서·내용·해시가 일관적인지 검증한다.
     */
    private void validateChunks(
        DocumentVersion documentVersion,
        List<DocumentChunk> chunks
    ) {
        if (chunks == null || chunks.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }

        for (int index = 0; index < chunks.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            if (chunk == null
                || chunk.getId() == null
                || chunk.getDocumentVersion() == null
                || !Objects.equals(chunk.getDocumentVersion().getId(), documentVersion.getId())
                || chunk.getChunkIndex() != index
                || !StringUtils.hasText(chunk.getChunkText())
                || !StringUtils.hasText(chunk.getContentHash())
                || !chunk.getContentHash().matches("[0-9a-f]{64}")) {
                throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
            }
        }
    }

    /**
     * 외부 호출 전에 만든 작업 Snapshot이 현재 잠근 버전과 Job 모델을 그대로 가리키는지 확인한다.
     */
    private void validatePreparedTarget(
        EmbeddingWork preparedWork,
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel
    ) {
        if (preparedWork == null
            || !Objects.equals(preparedWork.documentVersionId(), documentVersion.getId())
            || !Objects.equals(preparedWork.embeddingModelId(), embeddingModel.getId())
            || !Objects.equals(preparedWork.modelName(), embeddingModel.getModelName())
            || preparedWork.dimension() != embeddingModel.getDimension()) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
    }

    /**
     * 외부 호출 도중 Chunk Set이 교체되거나 내용이 바뀌지 않았는지 Snapshot과 다시 비교한다.
     *
     * <p>Vector가 다른 텍스트에 연결되는 것을 막기 위해 개수뿐 아니라 ID, 순서, 본문과
     * 내용 해시를 모두 일치시킨다.
     */
    private void validatePreparedChunks(
        EmbeddingWork preparedWork,
        List<DocumentChunk> chunks
    ) {
        if (preparedWork.chunks().size() != chunks.size()) {
            throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
        }

        for (int index = 0; index < chunks.size(); index++) {
            ChunkSnapshot snapshot = preparedWork.chunks().get(index);
            DocumentChunk chunk = chunks.get(index);
            if (snapshot == null
                || !Objects.equals(snapshot.chunkId(), chunk.getId())
                || snapshot.chunkIndex() != chunk.getChunkIndex()
                || !Objects.equals(snapshot.chunkText(), chunk.getChunkText())
                || !Objects.equals(snapshot.contentHash(), chunk.getContentHash())) {
                throw new DocGridException(ErrorCode.DOCUMENT_CHUNKS_INCONSISTENT);
            }
        }
    }

    /**
     * 버전 상태와 현재 모델의 Vector 개수를 대조해 작업 필요 여부와 완료 재생을 판정한다.
     *
     * <p>Vector가 Chunk보다 많거나 허용되지 않은 버전에 Vector가 존재하면 저장 집합이
     * 손상된 것으로 간주한다. EMBEDDING 상태의 부분 집합만 복구 작업으로 이어갈 수 있다.
     */
    private EmbeddingState resolveState(
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        int chunkCount
    ) {
        long embeddingCount = embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
            documentVersion.getId(),
            embeddingModel.getId()
        );

        if (embeddingCount > chunkCount) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        if (documentVersion.getStatus() == DocumentVersionStatus.CHUNKED) {
            if (embeddingCount == chunkCount) {
                return EmbeddingState.REPLAY;
            }
            return EmbeddingState.WORK;
        }
        if (documentVersion.getStatus() == DocumentVersionStatus.EMBEDDING) {
            if (embeddingCount < chunkCount) {
                return EmbeddingState.WORK;
            }
            if (embeddingCount == chunkCount) {
                return EmbeddingState.REPLAY;
            }
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        if (embeddingCount != 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
        throw new DocGridException(ErrorCode.DOCUMENT_VERSION_EMBEDDING_NOT_ALLOWED);
    }

    /**
     * 검증된 Draft를 ACTIVE Embedding Entity로 변환하되 이미 저장된 Chunk Vector는 보존한다.
     *
     * <p>복구 Job에서는 누락된 Chunk만 추가해야 기존 Vector ID와 검색 참조가 유지되므로,
     * 같은 버전·모델에 존재하는 Chunk ID를 먼저 읽고 나머지만 생성한다.
     */
    private List<Embedding> toEntities(
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        List<DocumentChunk> chunks,
        List<DocumentEmbeddingDraft> drafts
    ) {
        if (documentVersion.getDocument() == null
            || documentVersion.getDocument().getId() == null
            || drafts == null
            || drafts.size() != chunks.size()) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        List<Embedding> embeddings = new ArrayList<>(drafts.size());
        Set<Long> existingChunkIds = Set.copyOf(
            embeddingRepository.findChunkIdsByDocumentVersionIdAndEmbeddingModelId(
                documentVersion.getId(),
                embeddingModel.getId()
            )
        );
        for (int index = 0; index < drafts.size(); index++) {
            DocumentChunk chunk = chunks.get(index);
            DocumentEmbeddingDraft draft = drafts.get(index);
            float[] vector = validateDraft(draft, chunk, embeddingModel.getDimension());
            if (existingChunkIds.contains(chunk.getId())) {
                // 기존 Vector는 보존하고 누락 Chunk만 채워 자동복구가 물리 삭제를 요구하지 않게 한다.
                continue;
            }
            embeddings.add(Embedding.builder()
                .chunk(chunk)
                .document(documentVersion.getDocument())
                .documentVersion(documentVersion)
                .embeddingModel(embeddingModel)
                .vector(vector)
                .dimension(embeddingModel.getDimension())
                .vectorHash(draft.vectorHash())
                .status(EmbeddingStatus.ACTIVE)
                .build());
        }
        return embeddings;
    }

    /**
     * Embedding Draft가 대상 Chunk와 일치하고 Vector 차원·유한성·해시가 올바른지 검증한다.
     *
     * @return 검증을 통과한 Vector 값
     */
    private float[] validateDraft(
        DocumentEmbeddingDraft draft,
        DocumentChunk chunk,
        int expectedDimension
    ) {
        if (draft == null
            || !Objects.equals(draft.chunkId(), chunk.getId())
            || draft.chunkIndex() != chunk.getChunkIndex()
            || !Objects.equals(draft.contentHash(), chunk.getContentHash())
            || !StringUtils.hasText(draft.vectorHash())
            || !draft.vectorHash().matches("[0-9a-f]{64}")) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        float[] vector = draft.vector();
        EmbeddingVectorSupport.validate(vector, expectedDimension);
        if (!draft.vectorHash().equals(EmbeddingVectorSupport.calculateHash(vector))) {
            throw new DocGridException(ErrorCode.EMBEDDING_VECTOR_INVALID);
        }
        return vector;
    }

    /**
     * 현재 버전·모델과 처리 개수를 Embedding 완료 결과로 조립한다.
     */
    private CompletionResult result(
        Long jobId,
        Long attemptId,
        DocumentVersion documentVersion,
        EmbeddingModel embeddingModel,
        int chunkCount,
        boolean created
    ) {
        return new CompletionResult(
            jobId,
            attemptId,
            documentVersion.getId(),
            embeddingModel.getId(),
            chunkCount,
            chunkCount,
            documentVersion.getStatus(),
            created
        );
    }

    private enum EmbeddingState {
        WORK,
        REPLAY
    }

    /**
     * 외부 호출에 필요한 Version·Model 식별자와 정렬된 Chunk 값의 불변 Snapshot.
     */
    public record EmbeddingWork(
        Long documentVersionId,
        Long embeddingModelId,
        String modelName,
        int dimension,
        List<ChunkSnapshot> chunks
    ) {

        /**
         * 호출자가 보관한 Chunk 목록 변경이 작업 Snapshot에 전파되지 않도록 방어 복사한다.
         */
        public EmbeddingWork {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * 외부 호출에 전달하는 단일 Chunk의 식별자, 순서, Text, Embedding 입력, Token 수와 내용 Hash Snapshot.
     *
     * <p>{@code chunkText}는 저장된 원문으로 완료 단계의 일치 검증에 쓰고, {@code embeddingText}는
     * Section 경로가 붙을 수 있는 Vector 생성 입력이다. 경로가 없으면 두 값은 같다.
     */
    public record ChunkSnapshot(
        Long chunkId,
        int chunkIndex,
        String chunkText,
        String embeddingText,
        int tokenCount,
        String contentHash
    ) {

        /**
         * Section 경로 없이 원문을 그대로 Embedding 입력으로 쓰는 Snapshot을 만든다.
         */
        public ChunkSnapshot(
            Long chunkId,
            int chunkIndex,
            String chunkText,
            int tokenCount,
            String contentHash
        ) {
            this(chunkId, chunkIndex, chunkText, chunkText, tokenCount, contentHash);
        }
    }

    /**
     * 준비 Transaction이 전달하는 외부 작업 Snapshot 또는 기존 완료 결과 중 하나를 표현한다.
     */
    public record PreparationResult(
        EmbeddingWork work,
        CompletionResult replayResult
    ) {

        /**
         * 외부 Embedding Provider 호출이 필요한 준비 결과를 생성한다.
         */
        static PreparationResult work(EmbeddingWork work) {
            return new PreparationResult(work, null);
        }

        /**
         * 이미 완성된 Vector Set을 재사용하는 준비 결과를 생성한다.
         */
        static PreparationResult replay(CompletionResult replayResult) {
            return new PreparationResult(null, replayResult);
        }

        /**
         * 외부 호출 없이 기존 완료 결과를 반환해야 하는지 확인한다.
         */
        public boolean isReplay() {
            return replayResult != null;
        }
    }

    /**
     * 저장 생성 여부와 API 응답에 필요한 문서 Embedding Set 요약을 전달한다.
     */
    public record CompletionResult(
        Long jobId,
        Long attemptId,
        Long documentVersionId,
        Long embeddingModelId,
        int chunkCount,
        int embeddingCount,
        DocumentVersionStatus documentVersionStatus,
        boolean created
    ) {
    }
}
