package com.opensource.docgrid.domain.document.lifecycle;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;
import com.opensource.docgrid.domain.document.observability.StorageOrphanMetrics;
import com.opensource.docgrid.domain.document.service.StorageOrphanInspectionService;

/** 고아 Object Scheduler가 완성 결과와 실패를 서로 다른 Metrics 경로로 기록하는지 검증한다. */
@DisplayName("StorageOrphanInspectionScheduler 테스트")
class StorageOrphanInspectionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    private StorageOrphanInspectionService inspectionService;
    private StorageOrphanMetrics metrics;
    private StorageOrphanInspectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        inspectionService = mock(StorageOrphanInspectionService.class);
        metrics = mock(StorageOrphanMetrics.class);
        scheduler = new StorageOrphanInspectionScheduler(
            inspectionService,
            metrics,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("전체 탐지가 성공하면 결과와 완료 시각을 기록한다")
    void inspect_recordsCompletedSnapshot() {
        StorageOrphanInspectionResult result = new StorageOrphanInspectionResult(4, 2, 1, 20, 1, 0);
        given(inspectionService.inspect()).willReturn(result);

        scheduler.inspect();

        then(metrics).should().recordSuccess(result, NOW);
        then(metrics).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("전체 탐지가 실패하면 실패만 기록하고 예외를 다음 주기와 격리한다")
    void inspect_recordsFailureWithoutPublishingSnapshot() {
        given(inspectionService.inspect()).willThrow(new IllegalStateException("storage unavailable"));

        scheduler.inspect();

        then(metrics).should().recordFailure();
        then(metrics).shouldHaveNoMoreInteractions();
    }
}
