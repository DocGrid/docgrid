package com.opensource.docgrid.domain.dashboard.service.command;

import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.global.exception.DocGridException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 재처리 버튼 클릭을 A 담당자의 {@link EmbeddingJobManualRetryService}로 위임하고,
 * 성공 시 최신 대시보드 집계를 WebSocket으로 push하는 Command Service. 알림 인가 조회가
 * 실패해도 이미 커밋된 재처리 결과를 HTTP 실패로 잘못 보고하지 않는다.
 *
 * <p>{@code embedding_jobs}를 직접 update하지 않는다 — 상태 전환은 전부 A의 Service를 경유한다.
 * FAILED 목록 조회만 {@link EmbeddingJobRepository}를 직접 읽는다(쓰기가 아니므로 A/B 경계 위반이
 * 아니다).
 *
 * <p>다른 Command Service와 달리 클래스 레벨 {@code @Transactional}을 의도적으로 두지 않는다.
 * {@link #retryAllFailedJobs()}가 여러 Job에 대해 {@code retry()}를 순회 호출하는데, 이 메서드
 * 자체가 트랜잭션을 열면 각 호출이 그 트랜잭션에 참여(REQUIRED)하게 되어, 중간에 한 Job이 실패해
 * 예외가 전파되는 순간 스프링이 트랜잭션을 rollback-only로 표시한다. 그러면 이후 catch로 예외를
 * 잡아도 커밋 시점에 이전에 성공한 Job들까지 전부 롤백된다 — "개별 실패는 건너뛰고 성공 건수만
 * 집계한다"는 요구사항 자체가 깨진다. 이 클래스가 트랜잭션을 열지 않아야 {@code retry()} 호출마다
 * A Service 자신의 {@code @Transactional}이 매번 독립된 새 트랜잭션을 만들어 각자 커밋된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingJobRetryService {

    private static final String RETRY_ALL_MESSAGE_FORMAT =
        "재처리 %d건, 대상 제외 %d건, 오류 %d건입니다.";

    private final EmbeddingJobManualRetryService embeddingJobManualRetryService;
    private final EmbeddingJobRepository embeddingJobRepository;
    private final DashboardQueryService dashboardQueryService;
    private final DashboardWebSocketController dashboardWebSocketController;

    public ManualRetriedIndexingJobResponse retryJob(Long jobId) {
        // 1. 상태 전환은 A Service에 위임한다 — 여기서 예외가 나면(404/409) 그대로 전파시킨다.
        ManualRetriedIndexingJobResponse response = embeddingJobManualRetryService.retry(jobId);
        // 2. 재처리 성공 후에만 최신 집계를 다시 계산해서 push한다.
        pushDashboardUpdateAfterRetry();
        return response;
    }

    public RetryAllJobsResponse retryAllFailedJobs() {
        // 1. 전체 Entity 대신 FAILED Job ID Snapshot만 읽고 실제 조건은 각 잠금 Transaction에서 재검증한다.
        List<Long> failedJobIds = embeddingJobRepository.findIdsByStatusOrderByIdAsc(
            EmbeddingJobStatus.FAILED
        );

        // 2. 한 건씩 독립적으로 재처리하고 대상 제외와 예상 밖 실행 오류를 분리한다.
        int retriedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        for (Long failedJobId : failedJobIds) {
            try {
                embeddingJobManualRetryService.retry(failedJobId);
                retriedCount++;
            } catch (DocGridException exception) {
                skippedCount++;
                log.warn(
                    "전체 재처리 대상 제외: jobId={}, errorCode={}",
                    failedJobId,
                    exception.getErrorCode()
                );
            } catch (RuntimeException exception) {
                failedCount++;
                log.error("전체 재처리 중 예상 밖 오류: jobId={}", failedJobId, exception);
            }
        }

        // 3. 실제로 바뀐 게 있을 때만(1건 이상 성공) push한다 — 전부 실패하면 push할 변경사항이 없다.
        if (retriedCount > 0) {
            pushDashboardUpdateAfterRetry();
        }

        return new RetryAllJobsResponse(
            failedJobIds.size(),
            retriedCount,
            skippedCount,
            failedCount,
            RETRY_ALL_MESSAGE_FORMAT.formatted(retriedCount, skippedCount, failedCount)
        );
    }

    private void pushDashboardUpdateAfterRetry() {
        try {
            // 재처리는 이미 커밋됐다. 대시보드 알림만 실패하면 운영 결과를 실패로 뒤집지 않는다.
            dashboardWebSocketController.sendDashboardUpdate(dashboardQueryService.getSummary());
        } catch (RuntimeException exception) {
            log.warn("재처리는 완료됐으나 대시보드 알림을 보내지 못했습니다. cause={}",
                exception.getClass().getSimpleName());
        }
    }
}
