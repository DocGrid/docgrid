package com.opensource.docgrid.domain.rag.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PROCESSING 상태로 너무 오래 남아있는 RagResponse를 강제로 FAILED 종료시키는 안전망 (#286).
 *
 * <p>RagJobWorker는 Ollama가 GPU 1개로 순차 처리된다는 전제 위에서 동작하는데(RagJobWorker
 * 클래스 Javadoc 참고), 이 순차 처리 자체에는 대기 시간 상한이 없다 — 앞선 job이 정상 흐름이든
 * hang이든 끝나야 다음 job이 처리된다. 이 스위퍼는 RagJobWorker와 별도의 주기로 폴링하며,
 * 일정 시간(rag.worker.stale-threshold) 이상 PROCESSING인 job을 찾아 기존 extractive fallback
 * 답변({@link RagFacade#failIfStillProcessing})으로 강제 종료시켜, 사용자가 무기한 대기하지
 * 않도록 상한을 만든다.
 *
 * <p>RagJobWorker가 같은 job을 이 스위퍼와 거의 동시에 정상 완료할 수 있는 경합은
 * {@link RagFacade#failIfStillProcessing}이 내부적으로 쓰는 조건부 UPDATE로 방지된다 — 이미
 * 끝난 job이면 아무 일도 일어나지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagJobTimeoutSweeper {

    private final RagResponseRepository ragResponseRepository;
    private final RagFacade ragFacade;
    private final RagWebSocketController ragWebSocketController;

    @Value("${rag.worker.stale-threshold:90s}")
    private Duration staleThreshold;

    /**
     * stale-threshold 이상 PROCESSING으로 남아있는 job을 전부 찾아 하나씩 강제 종료한다.
     * {@link RagFacade#failIfStillProcessing}이 job마다 독립된 트랜잭션으로 실행되므로 DB
     * 반영은 job 단위로 원자적이지만, 그것만으로는 "하나가 예외를 던지면 나머지 job이 이번
     * sweep 주기에서 통째로 건너뛰어지는" 문제까지 막아주지 않는다 — 그래서 job 하나하나를
     * try/catch로 격리해, 하나가 실패해도 나머지 stale job은 계속 처리한다.
     */
    @Scheduled(fixedDelayString = "${rag.worker.timeout-sweep-interval:15s}")
    public void sweep() {
        /**
         * cutoff는 "정확히 staleThreshold(90초) 전 시점" 하나다 — created_at이 이보다 이전인
         * job은 곧 생성된 지 90초보다 더 지났다는 뜻이라, 아래 쿼리는 그런 job을 전부 찾는다.
         */
        LocalDateTime cutoff = LocalDateTime.now().minus(staleThreshold);
        List<RagResponse> staleJobs =
            ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, cutoff);

        for (RagResponse job : staleJobs) {
            try {
                Long queryId = job.getQuery().getId();
                String userEmail = job.getQuery().getUser().getEmail();

                if (ragFacade.failIfStillProcessing(job.getId(), queryId)) {
                    log.warn("[RAG-SWEEP] stale job force-failed queryId={} responseId={}", queryId, job.getId());
                    ragWebSocketController.notifyAnswerReady(userEmail, queryId);
                }
            } catch (Exception e) {
                log.error("[RAG-SWEEP] stale job 처리 중 예외 발생, 다음 job으로 계속 진행 responseId={}",
                    job.getId(), e);
            }
        }
    }
}
