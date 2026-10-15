package com.opensource.docgrid.domain.rag.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.rag.config.RagExecutionConfig;
import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseClaimService;
import com.opensource.docgrid.domain.rag.service.command.RagJobClaim;

import lombok.extern.slf4j.Slf4j;

/**
 * PROCESSING 상태인 RagResponse를 최대 {@code rag.worker.max-concurrency}건까지 동시에 꺼내
 * 처리하는 경량 Worker (#218, 병렬화는 #340).
 *
 * <p>여러 백엔드 인스턴스가 같은 DB 큐를 소비한다. 각 claim의 token·lease를 heartbeat로
 * 유지하고, 완료 쓰기에서도 token을 검사해 잃어버린 소유권의 늦은 결과를 폐기한다.
 *
 * <p>동시성은 두 계층으로 제한된다: ① {@link #ragWorkerSlots}(로컬 {@link Semaphore})를 먼저
 * 확보해야 DB claim을 시도하고 — 이 순서 덕분에 "claim은 됐는데 실행할 스레드가 없는" 상태가
 * 생기지 않는다. ② claim 자체는 {@link RagResponseClaimService}가 {@code FOR UPDATE SKIP
 * LOCKED} + token·lease로 여러 스레드가 동시에 같은 job을 집지 못하게 막는다. 실제
 * 처리는 {@link #ragWorkerJobExecutor}(전용 {@link ThreadPoolExecutor})에서 실행되어,
 * {@link #processNext()} 자체는 claim만 하고 즉시 반환한다 — Ollama 호출(최대
 * {@code ollama.generate-deadline})로 폴링 스레드가 막히지 않는다.
 *
 * <p>다른 앱의 기동은 유효한 claim을 지우지 않는다. 죽은 앱의 작업만 lease 만료 뒤 재획득한다.
 */
@Component
@Slf4j
public class RagJobWorker {

    private final RagResponseRepository ragResponseRepository;
    private final RagResponseClaimService ragResponseClaimService;
    private final RagFacade ragFacade;
    private final RagWebSocketController ragWebSocketController;
    private final Semaphore ragWorkerSlots;
    private final ThreadPoolExecutor ragWorkerJobExecutor;
    private final ConcurrentHashMap<Long, RagJobClaim> activeClaims = new ConcurrentHashMap<>();

    public RagJobWorker(
        RagResponseRepository ragResponseRepository,
        RagResponseClaimService ragResponseClaimService,
        RagFacade ragFacade,
        RagWebSocketController ragWebSocketController,
        @Qualifier(RagExecutionConfig.RAG_WORKER_SLOTS) Semaphore ragWorkerSlots,
        @Qualifier(RagExecutionConfig.RAG_WORKER_JOB_EXECUTOR) ThreadPoolExecutor ragWorkerJobExecutor
    ) {
        this.ragResponseRepository = ragResponseRepository;
        this.ragResponseClaimService = ragResponseClaimService;
        this.ragFacade = ragFacade;
        this.ragWebSocketController = ragWebSocketController;
        this.ragWorkerSlots = ragWorkerSlots;
        this.ragWorkerJobExecutor = ragWorkerJobExecutor;
    }

    /** 실행 중인 작업만 5초마다 연장한다. 실패하면 이전 token의 완료 쓰기는 DB에서 거부된다. */
    @Scheduled(fixedDelayString = "${rag.worker.lease-heartbeat-interval:5s}")
    public void renewActiveClaims() {
        for (RagJobClaim claim : activeClaims.values()) {
            try {
                if (!ragResponseClaimService.renew(claim)) {
                    activeClaims.remove(claim.jobId(), claim);
                    log.warn("[RAG-WORKER] lease 소유권 상실 jobId={}", claim.jobId());
                }
            } catch (RuntimeException e) {
                log.warn("[RAG-WORKER] lease 연장 실패 jobId={}", claim.jobId(), e);
            }
        }
    }

    /**
     * 1초마다 실행되어, 로컬 슬롯이 남아있는 한 PROCESSING job을 계속 claim해 전용 Executor에
     * 넘긴다. 슬롯이 없거나(이미 정원만큼 처리 중) 대기 중인 job이 없으면 그 자리에서 멈춘다.
     *
     * <p>이 메서드 자체는 기다리는 코드가 없다 — claim 한 번은 수 ms 안에 끝나고, 실제 Ollama
     * 호출(수십 초)은 {@link #executeClaimedJob}으로 넘겨 이 메서드는 곧바로 다음 슬롯을 보러
     * 돌아간다. {@code tryAcquire()}를 {@code claimNext()}보다 먼저 부르는 순서도 이 때문에
     * 중요하다 — 순서가 바뀌면 "DB엔 claim됐다고 적혔는데 넘길 스레드가 없는" 유령 job이
     * 생긴다(클래스 Javadoc 참고).
     *
     * <p>{@code while (tryAcquire())}만으로 반복 횟수 상한이 자동으로 걸린다 — Semaphore의 총
     * permit 수가 이미 {@code max-concurrency}와 같아서, 별도 카운터 변수 없이도 이 루프가
     * {@code max-concurrency}번보다 더 돌 수 없다.
     */
    @Scheduled(fixedDelayString = "${rag.worker.polling-interval:1s}")
    public void processNext() {
        while (ragWorkerSlots.tryAcquire()) {
            Optional<RagJobClaim> claimedJob;
            try {
                claimedJob = ragResponseClaimService.claimNext();
            } catch (RuntimeException e) {
                ragWorkerSlots.release();
                log.error("[RAG-WORKER] claim 중 예외 발생", e);
                return;
            }

            if (claimedJob.isEmpty()) {
                // 대기 중인 job이 없다 — 미리 확보한 슬롯을 돌려주고 이번 폴링을 끝낸다.
                ragWorkerSlots.release();
                return;
            }

            RagJobClaim claim = claimedJob.get();
            Long jobId = claim.jobId();
            activeClaims.put(jobId, claim);
            try {
                ragWorkerJobExecutor.execute(() -> executeClaimedJob(claim));
            } catch (RejectedExecutionException e) {
                /**
                 * 슬롯을 먼저 확보했으므로 이론상 도달하지 않아야 하지만(Executor 정원 =
                 * Semaphore 총 permit 수), 종료 절차 중 등 극단적 상황에 대비한 방어다.
                 */
                ragWorkerSlots.release();
                activeClaims.remove(jobId, claim);
                log.warn("[RAG-WORKER] 실행 제출이 거부됨 jobId={}", jobId);
                return;
            }
        }
    }

    /**
     * claim된 job 하나를 실제로 처리한다 — 전용 Executor 스레드에서 실행된다.
     *
     * <p>처리 결과는 세 갈래로 갈린다: ①정상 성공 — WebSocket 알림. ②{@link
     * OptimisticLockingFailureException} — 다른 트랜잭션이 이미 이 job을 처리했다는 뜻이라
     * 이미 올바르게 반영된 결과를 덮어쓰지 않도록 조용히 넘어간다(알림도 안 보낸다 — 그건
     * 먼저 처리한 쪽의 몫). ③그 외 예상 못한 예외 — FAILED로 강제 확정한 뒤 알림까지 보낸다
     * (실패했어도 화면이 영원히 로딩중으로 안 남도록).
     *
     * <p>①/③ 모두 알림은 {@code processJob}/{@code markUnexpectedFailure}가 반환하는
     * boolean을 확인한 뒤에만 보낸다 — RagJobTimeoutSweeper가 이 job을 이미 먼저 FAILED로
     * 확정해뒀다면(#288) 두 메서드 다 실제로는 아무것도 안 바꾸고 false를 반환하는데, 이 경우
     * 스위퍼가 이미 보낸 알림 외에 Worker가 중복으로 또 보낼 이유가 없다.
     *
     * <p>어떤 경로로 끝나든 {@code finally}에서 반드시 슬롯을 반환한다 — 안 그러면 이 Worker가
     * 처리 가능한 동시성이 영구히 줄어든다.
     */
    private void executeClaimedJob(RagJobClaim claim) {
        Long jobId = claim.jobId();
        try {
            RagResponse job = ragResponseRepository.findWithQueryAndUserById(jobId).orElse(null);
            if (job == null) {
                /**
                 * claim 직후 이 job이 통째로 사라지는 건 극단적 상황(예: 테스트 데이터 정리)에서만
                 * 가능하다 — processJob() 자신도 findById로 다시 조회하므로 여기서는 방어만 한다.
                 */
                log.error("[RAG-WORKER] claim된 job을 찾을 수 없음 jobId={}", jobId);
                return;
            }
            Long queryId = job.getQuery().getId();
            String userEmail = job.getQuery().getUser().getEmail();

            try {
                if (ragFacade.processJob(jobId, claim.token())) {
                    ragWebSocketController.notifyAnswerReady(userEmail, queryId);
                }
            } catch (OptimisticLockingFailureException e) {
                log.warn("[RAG-WORKER] job이 이미 다른 트랜잭션에서 처리된 것으로 보임(경합) queryId={}", queryId);
            } catch (Exception e) {
                log.error("[RAG-WORKER] job 처리 중 예상치 못한 예외 queryId={}", queryId, e);
                if (ragFacade.markUnexpectedFailure(jobId, claim.token(), e.getMessage())) {
                    ragWebSocketController.notifyAnswerReady(userEmail, queryId);
                }
            }
        } finally {
            activeClaims.remove(jobId, claim);
            ragWorkerSlots.release();
        }
    }
}
