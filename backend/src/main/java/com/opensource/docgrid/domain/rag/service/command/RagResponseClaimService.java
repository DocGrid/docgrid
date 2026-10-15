package com.opensource.docgrid.domain.rag.service.command;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;

import lombok.RequiredArgsConstructor;

/**
 * PROCESSING 작업의 분산 소유권을 짧은 트랜잭션으로 획득·연장하는 서비스.
 *
 * <p>{@code embedding_jobs}의 {@code EmbeddingJobClaimService}와 같은 트랜잭션 경계 전략을
 * 쓴다 — 행 잠금({@code FOR UPDATE SKIP LOCKED})과 claim 표시를 하나의 짧은 트랜잭션으로
 * 묶어 커밋과 동시에 락을 풀고, 실제 Ollama 호출은 이 트랜잭션 밖에서(별도 스레드가
 * {@link com.opensource.docgrid.domain.rag.service.RagFacade#processJob}을 부르며) 진행한다.
 * RAG는 PENDING 같은 별도 대기 상태가 없어(생성 즉시 PROCESSING) {@code embedding_jobs}처럼
 * status 전이로 claim을 표시할 수 없다 — 대신 owner·token·lease를 기록한다.
 *
 * <p>"job을 고르는 단계"와 "실제로 처리하는 단계"는 시간상 완전히 분리된다 — 이 클래스는
 * 전자(수 ms)만 담당한다:
 * <pre>
 * claim 단계 (이 클래스, 수 ms)      : 행 잠금 → 새 token·lease 기록 → 커밋
 * 처리 단계 (RagFacade, 수십 초)     : DB 트랜잭션 밖에서 모델 호출. heartbeat로 lease 연장
 * </pre>
 * 잠금을 처리 단계까지 들고 있으면 그 job 하나 때문에 다른 claim 시도 전체가 수십 초씩 막힌다
 * — 그래서 잠금은 claim 단계에서만 잠깐 쓰고, 이후 소유권은 token과 lease로 판정한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RagResponseClaimService {

    private final RagResponseRepository ragResponseRepository;
    private final Clock clock;
    private final String owner = UUID.randomUUID().toString();
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(20);

    /**
     * 다음으로 처리할 PROCESSING job 하나를 claim한다. 클래스 Javadoc의 "짧은 트랜잭션"이
     * 정확히 이 메서드 호출 하나다 — 시작할 때 잠금이 걸리고, 리턴과 함께 커밋되며 claim이
     * 확정되고 잠금이 풀린다.
     *
     * @return claim에 성공한 job의 ID와 소유권. 대기 중인 job이 없으면 빈 값.
     */
    public Optional<RagJobClaim> claimNext() {
        LocalDateTime now = LocalDateTime.now(clock);
        return ragResponseRepository.findNextUnclaimedProcessingForUpdate(now)
            .map(job -> claim(job, now));
    }

    /**
     * 살아 있는 작업자의 lease만 연장한다. 토큰이 바뀌었거나 lease가 이미 만료됐으면
     * 이전 작업자는 소유권을 되찾지 못한다.
     */
    public boolean renew(RagJobClaim claim) {
        LocalDateTime now = LocalDateTime.now(clock);
        return ragResponseRepository.renewClaimLease(
            claim.jobId(), claim.owner(), claim.token(), now, now.plus(CLAIM_LEASE)
        ) == 1;
    }

    private RagJobClaim claim(RagResponse job, LocalDateTime now) {
        String token = UUID.randomUUID().toString();
        job.markClaimed(now, owner, token, now.plus(CLAIM_LEASE));
        return new RagJobClaim(job.getId(), owner, token);
    }
}
