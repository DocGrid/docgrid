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
 * <p>{@code domain/worker/config/WorkerExecutionConfig}(이 코드베이스에서 유일했던 커스텀
 * 스레드풀 선례)를 그대로 본떴다 — {@code core=max}인 {@link ThreadPoolExecutor} +
 * {@link SynchronousQueue}(큐잉 없음) + {@link ThreadPoolExecutor.AbortPolicy}(꽉 차면 즉시
 * 거부, 조용히 쌓아두지 않음)로 "설정된 동시성만 즉시 실행"을 보장한다.
 *
 * <p>{@code embedding_jobs}의 {@code WorkerExecutionSlotPool}(별도 클래스, 종료 플래그 +
 * introspection 메서드 포함)까지는 필요 없다 — RAG는 별도 워커 등록/우아한 종료 조율이나
 * 대시보드 노출 요구가 없어서, 같은 안전 성질(로컬 슬롯을 먼저 확보한 뒤에만 DB claim을
 * 시도해 "claim은 됐는데 실행할 스레드가 없는" 상태를 만들지 않는 것)을 순수
 * {@link Semaphore}만으로 재현한다.
 *
 * <p>{@code indexing.worker.enabled}로 켜고 끌 수 있는 인덱싱 워커와 달리, RAG Worker는
 * {@code RagSchedulingConfig}와 동일하게 조건 없이 항상 켜져 있어야 하는 검색 API 핵심
 * 경로라 {@code @ConditionalOnProperty}를 붙이지 않는다.
 */
@Configuration
public class RagExecutionConfig {

    public static final String RAG_WORKER_JOB_EXECUTOR = "ragWorkerJobExecutor";
    public static final String RAG_WORKER_SLOTS = "ragWorkerSlots";

    /**
     * 동시에 최대 {@code rag.worker.max-concurrency}건까지만 즉시 실행하는 무대기 Executor를 만든다.
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
     * DB claim을 시도하기 전에 먼저 확보해야 하는 로컬 실행 슬롯. 공정 모드(fair)로 만들어
     * 폴링 주기가 겹칠 때 대기가 한쪽으로 몰리지 않게 한다 — {@code WorkerExecutionSlotPool}의
     * 선택과 동일하다.
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
