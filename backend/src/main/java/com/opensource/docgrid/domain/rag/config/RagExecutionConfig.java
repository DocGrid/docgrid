package com.opensource.docgrid.domain.rag.config;

import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * RAG 답변 생성을 동시에 몇 건까지 처리할지 제한하는 실행 자원을 구성한다 (#340).
 *
 * <p>Ollama 호출은 한 건에 수십 초가 걸리고 그동안 호출한 스레드는 응답을 기다리며 멈춘다.
 * 동시에 N건을 처리하려면 그렇게 기다릴 스레드가 N개 있어야 한다. 이 클래스는 그 스레드 N개
 * ({@link #ragWorkerJobExecutor})와, 그중 몇 개가 놀고 있는지 세는 카운터
 * ({@link #ragWorkerSlots})를 만든다. 둘 다 {@code rag.worker.max-concurrency} 하나에서
 * 크기를 받는다. 실제로 job을 집고 처리하는 흐름은 {@code RagJobWorker}에 있다.
 *
 * <p>{@code domain/worker/config/WorkerExecutionConfig}(이 코드베이스에서 유일했던 커스텀
 * 스레드풀 선례)를 본떴다. 다만 {@code WorkerExecutionSlotPool}(종료 플래그 + introspection 포함)
 * 까지는 필요 없어 순수 {@link Semaphore}로 줄였다.
 *
 * <p>{@code indexing.worker.enabled}로 켜고 끌 수 있는 인덱싱 워커와 달리, RAG Worker는 검색
 * API의 핵심 경로라 {@code @ConditionalOnProperty}를 붙이지 않는다.
 */
@Configuration
public class RagExecutionConfig {

    public static final String RAG_WORKER_JOB_EXECUTOR = "ragWorkerJobExecutor";
    public static final String RAG_WORKER_SLOTS = "ragWorkerSlots";

    /**
     * 실제로 Ollama를 호출하고 기다리는 RAG 전용 스레드 N개.
     *
     * <p>{@code RagJobWorker.processNext()}는 job을 여기에 {@code execute()}로 던지고 즉시 돌아온다.
     * 그래서 폴링하는 스케줄러 스레드는 Ollama 호출에 막히지 않는다.
     *
     * <p>생성자 인자: core=max=N이라 스레드는 정확히 N개로 고정된다. {@link SynchronousQueue}는
     * 대기줄이 없다는 뜻 — 스레드가 다 바쁠 때 job을 던지면 풀 안에 쌓아두지 않고 즉시 거부한다.
     * 이미 DB에 "처리 중"이라고 적힌 job이 풀 안에서 몰래 대기하면 스위퍼가 그걸 모르고 강제
     * 종료하므로, 대기는 DB 테이블에서만 한다. {@link ThreadPoolExecutor.AbortPolicy}는 그 거부를
     * {@code RejectedExecutionException}으로 알린다 — 조용히 버리거나(DiscardPolicy) 폴러가 직접
     * 실행하는(CallerRunsPolicy) 것보다 예외를 받아 슬롯을 돌려주는 편이 맞다.
     * {@link CustomizableThreadFactory}는 로그에서 구분되게 스레드 이름을 {@code rag-worker-job-N}
     * 으로 붙인다.
     */
    @Bean(name = RAG_WORKER_JOB_EXECUTOR, destroyMethod = "shutdownNow")
    public ThreadPoolExecutor ragWorkerJobExecutor(
        @Value("${rag.worker.max-concurrency:2}") int maxConcurrency
    ) {
        validateMaxConcurrency(maxConcurrency);
        return new ThreadPoolExecutor(
            maxConcurrency,
            maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            new CustomizableThreadFactory("rag-worker-job-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * {@link #ragWorkerJobExecutor}의 스레드 N개 중 몇 개가 놀고 있는지 세는 카운터. DB에서 job을
     * 꺼내기(claim) 전에 이 값을 보고, 0이면 DB를 건드리지 않는다.
     *
     * <p>왜 필요한가: claim은 DB에 {@code claimed_at}을 적는 행위라, 꺼낸 뒤 Executor가 거부하면
     * "DB엔 처리 중인데 실제론 아무도 안 하는" 유령 job이 남는다 — 다음 폴링은
     * {@code claimed_at IS NULL}만 찾으니 다시 집지도 않는다. 그런데 {@link ThreadPoolExecutor}는
     * "지금 노는 스레드 있어?"를 믿을 만하게 물어볼 방법이 없고 {@code execute()}를 던져 봐야 안다.
     * 그래서 노는 스레드 수를 직접 세는 카운터를 옆에 두고, "슬롯 확보 → DB claim → execute"
     * 순서를 강제한다. permit 수와 스레드 수가 같은 설정값에서 나오므로 슬롯이 남았으면 노는
     * 스레드도 반드시 있다.
     *
     * <p>{@link Semaphore}는 OS의 그 세마포어다. {@code tryAcquire()}가 P(0이면 블로킹 대신 즉시
     * false), {@code release()}가 V. P는 폴러 스레드가, V는 수십 초 뒤 워커 스레드가
     * {@code finally}에서 부른다 — 잡은 쪽과 푸는 쪽이 달라도 되는 게 락이 아니라 세마포어인
     * 이유다. 공정 모드({@code true})는 {@code tryAcquire()}에는 효과가 없고 선례를 따라 둔 것이다.
     */
    @Bean(name = RAG_WORKER_SLOTS)
    public Semaphore ragWorkerSlots(@Value("${rag.worker.max-concurrency:2}") int maxConcurrency) {
        validateMaxConcurrency(maxConcurrency);
        return new Semaphore(maxConcurrency, true);
    }

    /**
     * {@code max-concurrency=0}(또는 음수)은 예외 없이 Worker를 영구 대기 상태로 만들 수 있어
     * ({@link Semaphore}는 permit 0으로도 생성 자체는 허용) 기동 시점에 바로 실패시킨다.
     */
    private void validateMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException(
                "rag.worker.max-concurrency는 1 이상이어야 합니다: " + maxConcurrency
            );
        }
    }
}
