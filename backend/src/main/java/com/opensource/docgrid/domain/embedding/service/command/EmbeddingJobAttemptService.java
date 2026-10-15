package com.opensource.docgrid.domain.embedding.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.embedding.converter.EmbeddingJobAttemptConverter;
import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 현재 Embedding Job Claim 소유권을 검증하고 실행 Attempt 시작을 기록하는 Command Service.
 *
 * <p>Job 행 잠금 뒤 공통 Validator에 Worker·Token·Lease 검증을 위임하고, Claim Token 멱등 조회,
 * Job별 다음 번호 할당과 Insert를 하나의 짧은 Transaction으로 묶는다. Job 상태나 이벤트는 변경하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EmbeddingJobAttemptService {

    private static final int FIRST_ATTEMPT_NO = 1;

    private final EmbeddingJobRepository embeddingJobRepository;
    private final EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    private final EmbeddingJobAttemptConverter embeddingJobAttemptConverter;
    private final EmbeddingJobOwnershipValidator embeddingJobOwnershipValidator;
    private final Clock clock;

    /**
     * 현재 Claim 세대의 Attempt를 생성하거나 같은 요청의 기존 Attempt를 반환한다.
     *
     * @param jobId Attempt를 시작할 Embedding Job 식별자
     * @param request 현재 Worker와 Claim Token
     * @return 외부 응답과 이번 호출의 신규 생성 여부
     */
    public StartResult start(Long jobId, StartEmbeddingJobAttemptRequest request) {
        // 1. 모든 Attempt 변경은 Job 행을 먼저 잠가 소유권 교체와 Job별 번호 할당을 직렬화한다.
        EmbeddingJob embeddingJob = embeddingJobRepository.findByIdForUpdate(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.EMBEDDING_JOB_NOT_FOUND));

        // 2. Lock 대기 중 경과한 시간까지 반영하도록 잠금 획득 뒤 현재 시각과 소유권을 검증한다.
        //    PostgreSQL TIMESTAMP 정밀도와 맞춰 최초 응답과 DB에서 읽은 재전송 응답의 시각을 동일하게 유지한다.
        LocalDateTime startedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        embeddingJobOwnershipValidator.validate(
            embeddingJob,
            request.workerId(),
            request.claimToken(),
            startedAt
        );

        // 3. 같은 현재 Claim의 재전송은 새 번호를 소비하지 않고 기존 Attempt를 그대로 반환한다.
        return embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(jobId, request.claimToken())
            .map(attempt -> replay(attempt, request.workerId()))
            .orElseGet(() -> create(embeddingJob, request.claimToken(), startedAt));
    }

    /**
     * 현재 Claim에 대응하는 새 Attempt 번호를 할당하고 실행 시작 이력을 저장한다.
     */
    private StartResult create(EmbeddingJob embeddingJob, String claimToken, LocalDateTime startedAt) {
        // 4. Job 행 잠금 안에서 최신 번호를 읽어 같은 Job의 다음 Attempt 번호를 결정한다.
        int nextAttemptNo = embeddingJobAttemptRepository
            .findTopByEmbeddingJobIdOrderByAttemptNoDesc(embeddingJob.getId())
            .map(attempt -> Math.addExact(attempt.getAttemptNo(), 1))
            .orElse(FIRST_ATTEMPT_NO);

        EmbeddingJobAttempt embeddingJobAttempt = embeddingJobAttemptRepository.save(
            EmbeddingJobAttempt.builder()
                .embeddingJob(embeddingJob)
                .workerNode(embeddingJob.getLockedByWorker())
                .attemptNo(nextAttemptNo)
                .claimToken(claimToken)
                .startedAt(startedAt)
                .build()
        );

        // 5. LAZY 연관 식별자는 Transaction 안에서 읽고, Controller에는 생성 여부만 별도로 전달한다.
        return new StartResult(
            embeddingJobAttemptConverter.toStartedResponse(embeddingJobAttempt),
            true
        );
    }

    /**
     * 같은 Claim Token으로 반복된 시작 요청이 원래 Worker의 Attempt인지 확인한 뒤 기존 응답을 재사용한다.
     */
    private StartResult replay(EmbeddingJobAttempt embeddingJobAttempt, Long workerId) {
        // 1. Token이 같더라도 저장된 Worker가 다르면 손상되거나 충돌한 소유권 데이터로 간주한다.
        if (embeddingJobAttempt.getWorkerNode() == null
            || !workerId.equals(embeddingJobAttempt.getWorkerNode().getId())) {
            log.error(
                "Embedding Job Attempt의 Worker 소유권 데이터가 일치하지 않습니다. jobId={}, attemptId={}, requestWorkerId={}",
                embeddingJobAttempt.getEmbeddingJob().getId(),
                embeddingJobAttempt.getId(),
                workerId
            );
            throw new DocGridException(ErrorCode.EMBEDDING_JOB_OWNERSHIP_INCONSISTENT);
        }

        // 2. 정상 재전송에는 기존 Attempt를 반환하고 Controller가 200 응답을 선택하도록 생성 여부를 내린다.
        return new StartResult(
            embeddingJobAttemptConverter.toStartedResponse(embeddingJobAttempt),
            false
        );
    }

    /**
     * Attempt 시작 응답과 HTTP 생성·재생 상태를 Controller에 함께 전달하는 내부 결과.
     *
     * <p>created 값은 HTTP 상태 선택에만 사용되며 JSON 응답 Body에는 포함되지 않는다.
     */
    public record StartResult(
        StartedEmbeddingJobAttemptResponse response,
        boolean created
    ) {
    }
}
