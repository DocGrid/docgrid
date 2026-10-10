package com.opensource.docgrid.domain.embedding.service.query;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;

import lombok.RequiredArgsConstructor;

/**
 * Lease가 만료된 PROCESSING Embedding Job ID를 복구 작업 분배용 Snapshot으로 조회하는 Query Service.
 *
 * <p>조회 결과 자체는 복구 정확성 경계가 아니며 행 잠금을 유지하지 않는다. 호출자는 각 후보를 별도
 * Command Transaction에서 잠그고 상태와 만료 시각을 다시 검증해야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmbeddingJobRecoveryQueryService {

    private final EmbeddingJobRepository embeddingJobRepository;

    /**
     * 오래 만료된 순서대로 설정된 최대 개수의 복구 후보 ID를 조회한다.
     */
    public List<Long> findExpiredJobIds(LocalDateTime recoveredAt, int batchSize) {
        if (recoveredAt == null || batchSize < 1) {
            throw new IllegalArgumentException("복구 기준 시각과 양수 Batch 크기가 필요합니다.");
        }
        return embeddingJobRepository.findExpiredProcessingJobIds(recoveredAt, batchSize);
    }

    /**
     * 시험 문서 버전에 속하는 만료 Job ID만 Snapshot으로 조회한다.
     */
    public List<Long> findExpiredJobIdsForDocumentVersion(
        LocalDateTime recoveredAt,
        int batchSize,
        Long documentVersionId
    ) {
        if (recoveredAt == null || batchSize < 1 || documentVersionId == null || documentVersionId < 1) {
            throw new IllegalArgumentException("복구 기준 시각, 양수 Batch 크기와 문서 버전 ID가 필요합니다.");
        }
        return embeddingJobRepository.findExpiredProcessingJobIdsForDocumentVersion(
            recoveredAt,
            batchSize,
            documentVersionId
        );
    }
}
