package com.opensource.docgrid.domain.dashboard.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.dashboard.controller.DashboardWebSocketController;
import com.opensource.docgrid.domain.dashboard.dto.response.DashboardSummaryResponse;
import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.domain.dashboard.service.query.DashboardQueryService;
import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingJobRetryService 단위 테스트")
class EmbeddingJobRetryServiceTest {

    @InjectMocks private EmbeddingJobRetryService embeddingJobRetryService;

    @Mock private EmbeddingJobManualRetryService embeddingJobManualRetryService;
    @Mock private EmbeddingJobRepository embeddingJobRepository;
    @Mock private DashboardQueryService dashboardQueryService;
    @Mock private DashboardWebSocketController dashboardWebSocketController;

    @Test
    @DisplayName("정상 케이스: 단건 재처리 성공 시 EmbeddingJobManualRetryService를 호출하고 최신 집계를 push한다")
    void retryJob_delegatesToAService_andPushesLatestSummary() {
        // Given
        ManualRetriedIndexingJobResponse retryResponse = mock(ManualRetriedIndexingJobResponse.class);
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(embeddingJobManualRetryService.retry(42L)).willReturn(retryResponse);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        ManualRetriedIndexingJobResponse result = embeddingJobRetryService.retryJob(42L);

        // Then
        assertThat(result).isSameAs(retryResponse);
        then(embeddingJobManualRetryService).should().retry(42L);
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("재처리 커밋 뒤 primary 판정 실패는 대시보드만 차단하고 재처리 성공 응답을 유지한다")
    void retryJob_keepsCommittedResult_whenDashboardAuthorizationFails() {
        ManualRetriedIndexingJobResponse retryResponse = mock(ManualRetriedIndexingJobResponse.class);
        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(embeddingJobManualRetryService.retry(42L)).willReturn(retryResponse);
        given(dashboardQueryService.getSummary()).willReturn(summary);
        willThrow(new IllegalStateException("primary unavailable"))
            .given(dashboardWebSocketController).sendDashboardUpdate(summary);

        assertThat(embeddingJobRetryService.retryJob(42L)).isSameAs(retryResponse);
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("정상 케이스: 대상 제외와 성공 건수를 분리하고 1건 이상 성공하면 push한다")
    void retryAllFailedJobs_separatesSkippedJobs_whenSomeJobsAreNotEligible() {
        // Given
        given(embeddingJobRepository.findIdsByStatusOrderByIdAsc(EmbeddingJobStatus.FAILED))
            .willReturn(List.of(1L, 2L, 3L));

        willReturn(mock(ManualRetriedIndexingJobResponse.class))
            .given(embeddingJobManualRetryService).retry(1L);
        willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID))
            .given(embeddingJobManualRetryService).retry(2L);
        willReturn(mock(ManualRetriedIndexingJobResponse.class))
            .given(embeddingJobManualRetryService).retry(3L);

        DashboardSummaryResponse summary = mock(DashboardSummaryResponse.class);
        given(dashboardQueryService.getSummary()).willReturn(summary);

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.scannedCount()).isEqualTo(3);
        assertThat(result.retriedCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.message()).isEqualTo("재처리 2건, 대상 제외 1건, 오류 0건입니다.");
        then(embeddingJobManualRetryService).should().retry(1L);
        then(embeddingJobManualRetryService).should().retry(2L);
        then(embeddingJobManualRetryService).should().retry(3L);
        then(dashboardWebSocketController).should().sendDashboardUpdate(summary);
    }

    @Test
    @DisplayName("예외 케이스: FAILED 작업이 없으면 0건으로 정상 응답하고 push하지 않는다")
    void retryAllFailedJobs_returnsZero_whenNoFailedJobs() {
        // Given
        given(embeddingJobRepository.findIdsByStatusOrderByIdAsc(EmbeddingJobStatus.FAILED))
            .willReturn(List.of());

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.scannedCount()).isZero();
        assertThat(result.retriedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(result.message()).isEqualTo("재처리 0건, 대상 제외 0건, 오류 0건입니다.");
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 모든 Job 재처리가 실패하면 push하지 않는다")
    void retryAllFailedJobs_doesNotPush_whenAllJobsFail() {
        // Given
        given(embeddingJobRepository.findIdsByStatusOrderByIdAsc(EmbeddingJobStatus.FAILED))
            .willReturn(List.of(1L));
        willThrow(new DocGridException(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID))
            .given(embeddingJobManualRetryService).retry(1L);

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then
        assertThat(result.retriedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        then(dashboardWebSocketController).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예외 케이스: 예상 밖 오류는 대상 제외와 분리하고 나머지 Job을 계속 처리한다")
    void retryAllFailedJobs_countsUnexpectedErrorsSeparately() {
        given(embeddingJobRepository.findIdsByStatusOrderByIdAsc(EmbeddingJobStatus.FAILED))
            .willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("unexpected"))
            .given(embeddingJobManualRetryService).retry(1L);
        willReturn(mock(ManualRetriedIndexingJobResponse.class))
            .given(embeddingJobManualRetryService).retry(2L);
        given(dashboardQueryService.getSummary()).willReturn(mock(DashboardSummaryResponse.class));

        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.retriedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
    }
}
