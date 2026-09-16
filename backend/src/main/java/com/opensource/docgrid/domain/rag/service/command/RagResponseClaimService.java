package com.opensource.docgrid.domain.rag.service.command;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;

import lombok.RequiredArgsConstructor;

/**
 * PROCESSING 중 아직 아무 Worker도 집지 않은 RagResponse 하나에 소유권을 부여하는 Command
 * Service (#340).
 *
 * <p>{@code embedding_jobs}의 {@code EmbeddingJobClaimService}와 같은 트랜잭션 경계 전략을
 * 쓴다 — 행 잠금({@code FOR UPDATE SKIP LOCKED})과 claim 표시를 하나의 짧은 트랜잭션으로
 * 묶어 커밋과 동시에 락을 풀고, 실제 Ollama 호출은 이 트랜잭션 밖에서(별도 스레드가
 * {@link com.opensource.docgrid.domain.rag.service.RagFacade#processJob}을 부르며) 진행한다.
 * RAG는 PENDING 같은 별도 대기 상태가 없어(생성 즉시 PROCESSING) {@code embedding_jobs}처럼
 * status 전이로 claim을 표시할 수 없다 — 대신 {@code claimed_at} 컬럼을 그 신호로 쓴다.
 *
 * <p>"job을 고르는 단계"와 "실제로 처리하는 단계"는 시간상 완전히 분리된다 — 이 클래스는
 * 전자(수 ms)만 담당한다:
 * <pre>
 * claim 단계 (이 클래스, 수 ms)      : 행 잠금 걸림 → claimed_at 기록 → 커밋과 동시에 잠금 풀림
 * 처리 단계 (RagFacade, 수십 초)     : 잠금 없이 Ollama 호출. claimed_at 값만으로 소유권 유지
 * </pre>
 * 잠금을 처리 단계까지 들고 있으면 그 job 하나 때문에 다른 claim 시도 전체가 수십 초씩 막힌다
 * — 그래서 잠금은 claim 단계에서만 잠깐 쓰고, 풀린 뒤의 장기 소유권 표시는 값 하나
 * ({@code claimed_at})로 넘긴다.
 *
 * <p>{@code embedding_jobs}와 달리 별도의 분산 Worker 등록/생존 검증은 하지 않는다 — RAG
 * Worker는 이 프로세스 안의 로컬 스레드일 뿐이라 그런 개념 자체가 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RagResponseClaimService {

    private final RagResponseRepository ragResponseRepository;
    private final Clock clock;

    /**
     * 다음으로 처리할 PROCESSING job 하나를 claim한다. 클래스 Javadoc의 "짧은 트랜잭션"이
     * 정확히 이 메서드 호출 하나다 — 시작할 때 잠금이 걸리고, 리턴과 함께 커밋되며 claim이
     * 확정되고 잠금이 풀린다.
     *
     * @return claim에 성공한 job의 id. 대기 중인 job이 없으면 빈 값.
     */
    public Optional<Long> claimNext() {
        return ragResponseRepository.findNextUnclaimedProcessingForUpdate()
            .map(this::claim);
    }

    /**
     * 앱 시작 시 1회 호출된다(#340 CodeRabbit 리뷰 반영). 이전 프로세스 인스턴스가 claim한 채
     * 완료하지 못한 job은 새 프로세스에서 영원히 재시도되지 않는다 — {@link
     * RagResponseRepository#releaseAllClaimsOnStartup}로 그 claim을 전부 풀어, 새로 뜬
     * Worker가 정상적으로 다시 claim해 처리할 수 있게 한다.
     *
     * @return 실제로 claim이 풀린 행 수
     */
    public int recoverStaleClaimsOnStartup() {
        return ragResponseRepository.releaseAllClaimsOnStartup();
    }

    private Long claim(RagResponse job) {
        job.markClaimed(LocalDateTime.now(clock));
        return job.getId();
    }
}
