package com.opensource.docgrid.domain.sync.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentChunkRepository;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingRepository;
import com.opensource.docgrid.domain.sync.config.SyncReconciliationProperties;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencyIssueType;
import com.opensource.docgrid.domain.sync.enums.SyncConsistencySeverity;

import lombok.RequiredArgsConstructor;

/**
 * 한 DocumentVersion의 원장 상태와 Job·Chunk·Vector 파생 상태를 읽기 전용으로 비교한다.
 *
 * <p>검사는 데이터 변경을 수행하지 않으며, 안전한 자동복구 가능 여부를 Observation에 표시한다.
 * currentVersion 모순, 기존 Vector 행의 상태 손상과 삭제 잔여물은 항상 보고 전용이다.
 */
@Component
@RequiredArgsConstructor
public class SyncConsistencyInspector {

    private static final Set<DocumentVersionStatus> PROCESSING_STATUSES = EnumSet.of(
        DocumentVersionStatus.UPLOADED,
        DocumentVersionStatus.PARSING,
        DocumentVersionStatus.CHUNKED,
        DocumentVersionStatus.EMBEDDING
    );
    private static final Set<EmbeddingJobStatus> LIVE_JOB_STATUSES = EnumSet.of(
        EmbeddingJobStatus.PENDING,
        EmbeddingJobStatus.PROCESSING
    );

    private final EmbeddingJobRepository embeddingJobRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final SyncReconciliationProperties properties;

    /**
     * 한 문서 버전의 원장 상태를 현재 활성 모델 기준의 Job·Chunk·Embedding 상태와 비교한다.
     *
     * <p>한 번의 검사에서 발견한 모든 문제를 반환하며, 서로 독립적인 이상을 첫 오류에서 중단하지 않는다.
     * Observation의 repairable 값은 기존 데이터를 덮어쓰지 않고 안전하게 보완 가능한 경우에만 참이다.
     */
    public List<SyncConsistencyObservation> inspect(
        DocumentVersion version,
        EmbeddingModel activeModel,
        LocalDateTime inspectedAt
    ) {
        // 1. 검사 대상 원장과 반복 사용되는 파생 데이터 개수·활성 Job 존재 여부를 한 번씩 조회한다.
        Document document = version.getDocument();
        List<SyncConsistencyObservation> observations = new ArrayList<>();
        long chunkCount = documentChunkRepository.countByDocumentVersionId(version.getId());
        long activeModelEmbeddingCount = embeddingRepository
            .countByDocumentVersionIdAndEmbeddingModelIdAndStatus(
                version.getId(), activeModel.getId(), EmbeddingStatus.ACTIVE
            );
        boolean liveJobExists = embeddingJobRepository.existsByDocumentVersionIdAndStatusIn(
            version.getId(), LIVE_JOB_STATUSES
        );

        // 2. 처리 중 버전이 실행 가능한 Job을 잃었는지 확인한다.
        inspectMissingJob(version, activeModel, liveJobExists, observations);

        // 3. 버전 단계에 필요한 Chunk와 현재 모델의 활성 Vector Set이 완성됐는지 확인한다.
        inspectChunksAndEmbeddings(version, activeModel, chunkCount, activeModelEmbeddingCount, observations);

        // 4. 검색 가능 문서의 currentVersion 포인터와 장기 정체 상태를 검사한다.
        inspectCurrentVersion(document, version, activeModel, observations);
        inspectStalledVersion(version, activeModel, liveJobExists, inspectedAt, observations);

        // 5. 삭제 문서에 검색 가능한 Vector가 남아 있는지 확인하고 모든 관찰 결과를 반환한다.
        inspectDeletedResidue(document, version, activeModel, observations);
        return observations;
    }

    /**
     * 처리 중 버전에 PENDING 또는 PROCESSING Job이 하나도 없는 상태를 자동복구 후보로 보고한다.
     */
    private void inspectMissingJob(
        DocumentVersion version,
        EmbeddingModel activeModel,
        boolean liveJobExists,
        List<SyncConsistencyObservation> observations
    ) {
        if (PROCESSING_STATUSES.contains(version.getStatus()) && !liveJobExists) {
            observations.add(observation(
                SyncConsistencyIssueType.MISSING_JOB,
                SyncConsistencySeverity.ERROR,
                version,
                activeModel,
                "{\"liveJob\":true}",
                "{\"liveJob\":false,\"versionStatus\":\"%s\"}".formatted(version.getStatus()),
                true
            ));
        }
    }

    /**
     * 버전 상태에 필요한 Chunk 존재 여부와 currentVersion의 활성 모델 Embedding 완전성을 검사한다.
     *
     * <p>현재 모델 Vector가 없지만 다른 모델 Vector가 Chunk 수만큼 존재하면 모델 불일치로 분류한다.
     * 일부 현재 모델 Vector만 누락된 경우에만 보존된 Vector를 유지하며 누락분을 채울 수 있다.
     * 삭제 문서는 삭제 Event가 Vector를 STALE로 내리므로 Embedding 완전성 대신 잔여물 검사만 적용한다.
     */
    private void inspectChunksAndEmbeddings(
        DocumentVersion version,
        EmbeddingModel activeModel,
        long chunkCount,
        long embeddingCount,
        List<SyncConsistencyObservation> observations
    ) {
        if ((version.getStatus() == DocumentVersionStatus.CHUNKED
            || version.getStatus() == DocumentVersionStatus.EMBEDDING
            || version.getStatus() == DocumentVersionStatus.INDEXED)
            && chunkCount == 0) {
            observations.add(observation(
                SyncConsistencyIssueType.MISSING_CHUNKS,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"chunkCount\":\">0\"}",
                "{\"chunkCount\":0}",
                false
            ));
        }
        boolean currentVersion = version.getDocument().getCurrentVersion() != null
            && Objects.equals(version.getDocument().getCurrentVersion().getId(), version.getId());
        if (version.getStatus() == DocumentVersionStatus.INDEXED
            && currentVersion
            && version.getDocument().getStatus() != DocumentStatus.DELETED
            && chunkCount > embeddingCount) {
            long currentModelCount = embeddingRepository.countByDocumentVersionIdAndEmbeddingModelId(
                version.getId(),
                activeModel.getId()
            );
            long allModelCount = embeddingRepository.countByDocumentVersionId(version.getId());
            SyncConsistencyIssueType issueType = currentModelCount == 0 && allModelCount >= chunkCount
                ? SyncConsistencyIssueType.MODEL_MISMATCH
                : SyncConsistencyIssueType.MISSING_EMBEDDINGS;
            observations.add(observation(
                issueType,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"activeEmbeddingCount\":%d}".formatted(chunkCount),
                ("{\"activeEmbeddingCount\":%d,\"currentModelEmbeddingCount\":%d,"
                    + "\"allModelEmbeddingCount\":%d}")
                    .formatted(embeddingCount, currentModelCount, allModelCount),
                currentModelCount == embeddingCount && currentModelCount < chunkCount
            ));
        }
    }

    /**
     * INDEXED 문서의 currentVersion이 존재하고 같은 문서에 속한 INDEXED 버전인지 검사한다.
     */
    private void inspectCurrentVersion(
        Document document,
        DocumentVersion version,
        EmbeddingModel activeModel,
        List<SyncConsistencyObservation> observations
    ) {
        if (document.getStatus() != DocumentStatus.INDEXED) {
            return;
        }
        DocumentVersion current = document.getCurrentVersion();
        boolean missingCurrentVersion = current == null;
        boolean inspectingCurrentVersion = current != null && Objects.equals(current.getId(), version.getId());
        if (missingCurrentVersion
            || (inspectingCurrentVersion
                && (version.getStatus() != DocumentVersionStatus.INDEXED
                    || current.getDocument() == null
                    || !Objects.equals(current.getDocument().getId(), document.getId())))) {
            observations.add(observation(
                SyncConsistencyIssueType.INVALID_CURRENT_VERSION,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"currentVersionStatus\":\"INDEXED\",\"sameDocument\":true}",
                current == null
                    ? "{\"currentVersion\":null}"
                    : "{\"currentVersionStatus\":\"%s\"}".formatted(version.getStatus()),
                false
            ));
        }
    }

    /**
     * 처리 중 버전의 최종 갱신 시각이 허용 임계값보다 오래됐는지 검사한다.
     *
     * <p>활성 Job이 있으면 지연 가능성이 있어 WARNING, Job도 없으면 진행 수단을 잃은 상태로 보고
     * ERROR 심각도를 사용한다. 정체 원인을 단정할 수 없으므로 자동복구 대상으로 표시하지 않는다.
     */
    private void inspectStalledVersion(
        DocumentVersion version,
        EmbeddingModel activeModel,
        boolean liveJobExists,
        LocalDateTime inspectedAt,
        List<SyncConsistencyObservation> observations
    ) {
        if (!PROCESSING_STATUSES.contains(version.getStatus())
            || version.getUpdatedAt() == null
            || !version.getUpdatedAt().isBefore(inspectedAt.minus(properties.getStalledThreshold()))) {
            return;
        }
        observations.add(observation(
            SyncConsistencyIssueType.STALLED_VERSION,
            liveJobExists ? SyncConsistencySeverity.WARNING : SyncConsistencySeverity.ERROR,
            version,
            activeModel,
            "{\"updatedAfter\":\"%s\"}".formatted(inspectedAt.minus(properties.getStalledThreshold())),
            "{\"updatedAt\":\"%s\",\"liveJob\":%s}"
                .formatted(version.getUpdatedAt(), liveJobExists),
            false
        ));
    }

    /**
     * 삭제된 문서의 currentVersion에 ACTIVE Embedding이 남아 검색될 가능성이 있는지 검사한다.
     */
    private void inspectDeletedResidue(
        Document document,
        DocumentVersion version,
        EmbeddingModel activeModel,
        List<SyncConsistencyObservation> observations
    ) {
        if (document.getStatus() == DocumentStatus.DELETED
            && document.getCurrentVersion() != null
            && Objects.equals(document.getCurrentVersion().getId(), version.getId())
            && embeddingRepository.countByDocumentIdAndStatus(document.getId(), EmbeddingStatus.ACTIVE) > 0) {
            observations.add(observation(
                SyncConsistencyIssueType.DELETED_DOCUMENT_RESIDUE,
                SyncConsistencySeverity.CRITICAL,
                version,
                activeModel,
                "{\"activeEmbeddingCount\":0}",
                "{\"activeEmbeddingCount\":\">0\"}",
                false
            ));
        }
    }

    /**
     * 발견한 문제를 반복 실행에서도 동일하게 식별할 수 있는 Key와 함께 Observation으로 조립한다.
     *
     * <p>문서 포인터·삭제 잔여물은 문서 단위, 나머지 파생 상태는 버전·모델 단위 Key를 사용한다.
     */
    private SyncConsistencyObservation observation(
        SyncConsistencyIssueType type,
        SyncConsistencySeverity severity,
        DocumentVersion version,
        EmbeddingModel model,
        String expectedJson,
        String actualJson,
        boolean repairable
    ) {
        String issueKey = type == SyncConsistencyIssueType.INVALID_CURRENT_VERSION
            || type == SyncConsistencyIssueType.DELETED_DOCUMENT_RESIDUE
            ? "%s:DOCUMENT:%d".formatted(type, version.getDocument().getId())
            : "%s:VERSION:%d:MODEL:%d".formatted(type, version.getId(), model.getId());
        return new SyncConsistencyObservation(
            issueKey,
            type,
            severity,
            version.getDocument(),
            version,
            model,
            expectedJson,
            actualJson,
            repairable
        );
    }
}
