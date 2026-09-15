package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.opensource.docgrid.domain.rag.controller.RagWebSocketController;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseClaimService;

/**
 * RagJobWorker.processNext()의 디스패치 로직(#340)을 검증한다 — 슬롯을 먼저 확보한 뒤에만 claim을
 * 시도하는지, claim 결과에 따라 슬롯을 되돌려주는지, claim된 job을 Executor에 제출하는지가 검증
 * 범위다. Executor는 실제 스레드를 안 쓰고 제출된 Runnable을 캡처해 테스트 스레드에서 직접
 * 실행한다 — 그래야 실행 결과(성공/false/예외/OptimisticLocking 분기)를 결정적으로 검증할 수
 * 있다. 실제 OllamaClient 호출이나 DB 반영 여부(dirty checking이 실제로 먹히는지)는 이 테스트의
 * 목(mock) 구조로는 증명할 수 없어 검증 범위 밖이다 — RagJobWorkerIntegrationTest가 그 부분을
 * 담당한다.
 *
 * <p>{@code Semaphore}는 mock하지 않고 실제 인스턴스를 쓴다 — I/O가 없는 순수 카운터라, mock보다
 * 실제 객체로 "슬롯이 진짜 반환됐는지"를 permit 개수로 직접 확인하는 편이 더 간단하고 정확하다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagJobWorker 단위 테스트")
class RagJobWorkerTest {

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Mock
    private RagResponseClaimService ragResponseClaimService;

    @Mock
    private RagFacade ragFacade;

    @Mock
    private RagWebSocketController ragWebSocketController;

    @Mock
    private ThreadPoolExecutor ragWorkerJobExecutor;

    private Semaphore ragWorkerSlots;
    private RagJobWorker ragJobWorker;

    @BeforeEach
    void setUp() {
        ragWorkerSlots = new Semaphore(1);
        ragJobWorker = new RagJobWorker(
            ragResponseRepository, ragResponseClaimService, ragFacade, ragWebSocketController,
            ragWorkerSlots, ragWorkerJobExecutor
        );
    }

    @Test
    @DisplayName("슬롯이 없으면 claim 자체를 시도하지 않는다")
    void processNext_noSlotAvailable_neverClaims() {
        // 이미 다른 job이 유일한 슬롯을 쓰고 있는 상황을 재현한다. permit이 이미 있는 상태의
        // acquireUninterruptibly()는 즉시 반환되므로 실제로 블로킹되지 않는다.
        ragWorkerSlots.acquireUninterruptibly();

        ragJobWorker.processNext();

        then(ragResponseClaimService).should(never()).claimNext();
        then(ragWorkerJobExecutor).should(never()).execute(any());
    }

    @Test
    @DisplayName("claim할 job이 없으면 슬롯을 반환하고 Executor를 부르지 않는다")
    void processNext_claimEmpty_releasesSlotAndSkipsExecutor() {
        given(ragResponseClaimService.claimNext()).willReturn(Optional.empty());

        ragJobWorker.processNext();

        then(ragWorkerJobExecutor).should(never()).execute(any());
        assertThat(ragWorkerSlots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("claim에 성공하면 Executor에 제출하고, 정상 처리되면 요청자 본인에게만 완료를 push한다")
    void processNext_claimSucceeds_submitsAndNotifiesOwner() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        given(ragResponseRepository.findWithQueryAndUserById(999L)).willReturn(Optional.of(job));
        given(ragFacade.processJob(999L)).willReturn(true);

        ragJobWorker.processNext();
        runSubmittedTask();

        then(ragFacade).should(times(1)).processJob(999L);
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
        // 처리(성공적으로 실행된 Runnable)가 끝나면 finally에서 슬롯을 되돌려준다.
        assertThat(ragWorkerSlots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("경합(#288): processJob이 false를 반환하면(RagJobTimeoutSweeper가 이미 확정함) 알림을 보내지 않는다")
    void processNext_processJobLosesRace_doesNotNotify() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        given(ragResponseRepository.findWithQueryAndUserById(999L)).willReturn(Optional.of(job));
        given(ragFacade.processJob(999L)).willReturn(false);

        ragJobWorker.processNext();
        runSubmittedTask();

        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("processJob이 예상 밖 예외를 던지면 job을 FAILED로 확정하고, 알림은 그대로 보낸다")
    void processNext_unexpectedException_marksFailedAndNotifies() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        given(ragResponseRepository.findWithQueryAndUserById(999L)).willReturn(Optional.of(job));
        doThrow(new RuntimeException("예상 밖 버그")).when(ragFacade).processJob(999L);
        given(ragFacade.markUnexpectedFailure(999L, "예상 밖 버그")).willReturn(true);

        ragJobWorker.processNext();
        runSubmittedTask();

        then(ragFacade).should(times(1)).markUnexpectedFailure(999L, "예상 밖 버그");
        then(ragWebSocketController).should(times(1)).notifyAnswerReady("user@example.com", 100L);
    }

    @Test
    @DisplayName("다른 트랜잭션이 이미 같은 job을 처리했으면(낙관적 락 경합) FAILED로 덮어쓰지 않고 조용히 넘어간다")
    void processNext_optimisticLockingFailure_skipsWithoutOverwritingAsFailed() {
        RagResponse job = deepStubJob(999L, 100L, "user@example.com");
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        given(ragResponseRepository.findWithQueryAndUserById(999L)).willReturn(Optional.of(job));
        doThrow(new OptimisticLockingFailureException("경합")).when(ragFacade).processJob(999L);

        ragJobWorker.processNext();
        runSubmittedTask();

        then(ragFacade).should(never()).markUnexpectedFailure(any(), any());
        then(ragWebSocketController).should(never()).notifyAnswerReady(any(), any());
    }

    @Test
    @DisplayName("claim 중 예외가 나면 슬롯을 반환하고 Executor를 부르지 않는다")
    void processNext_claimThrows_releasesSlotAndSkipsExecutor() {
        given(ragResponseClaimService.claimNext()).willThrow(new RuntimeException("DB 오류"));

        ragJobWorker.processNext();

        then(ragWorkerJobExecutor).should(never()).execute(any());
        assertThat(ragWorkerSlots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Executor 제출이 거부되면(RejectedExecutionException) 슬롯을 반환한다")
    void processNext_executorRejects_releasesSlot() {
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        doThrow(new RejectedExecutionException()).when(ragWorkerJobExecutor).execute(any());

        ragJobWorker.processNext();

        assertThat(ragWorkerSlots.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("claim 직후 job이 사라졌으면(극단적 상황) processJob을 부르지 않고 슬롯만 반환한다")
    void processNext_claimedJobVanished_skipsProcessing() {
        given(ragResponseClaimService.claimNext()).willReturn(Optional.of(999L));
        given(ragResponseRepository.findWithQueryAndUserById(999L)).willReturn(Optional.empty());

        ragJobWorker.processNext();
        runSubmittedTask();

        then(ragFacade).should(never()).processJob(any());
        assertThat(ragWorkerSlots.availablePermits()).isEqualTo(1);
    }

    /** Executor에 제출된 Runnable을 캡처해 테스트 스레드에서 즉시(동기) 실행한다. */
    private void runSubmittedTask() {
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        then(ragWorkerJobExecutor).should(times(1)).execute(taskCaptor.capture());
        taskCaptor.getValue().run();
    }

    private RagResponse deepStubJob(Long jobId, Long queryId, String userEmail) {
        RagResponse job = mock(RagResponse.class, RETURNS_DEEP_STUBS);
        given(job.getQuery().getId()).willReturn(queryId);
        given(job.getQuery().getUser().getEmail()).willReturn(userEmail);
        return job;
    }
}
