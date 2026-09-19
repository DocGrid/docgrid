package com.opensource.docgrid.domain.document.lifecycle;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.dto.StorageOrphanInspectionResult;
import com.opensource.docgrid.domain.document.observability.StorageOrphanMetrics;
import com.opensource.docgrid.domain.document.service.StorageOrphanInspectionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 활성화된 인스턴스에서 저장소 고아 Object 전체 탐지를 주기적으로 실행한다.
 * 읽기 전용 1단계이므로 인스턴스 간 중복 실행은 허용하고 같은 인스턴스의 겹친 실행만 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage.orphan-gc", name = "enabled", havingValue = "true")
public class StorageOrphanInspectionScheduler {

    private final StorageOrphanInspectionService inspectionService;
    private final StorageOrphanMetrics metrics;
    private final Clock clock;
    private final AtomicBoolean inspecting = new AtomicBoolean(false);

    /** 저장소 전체를 한 번 탐지하고 완성된 결과 또는 실패만 관측 정보로 기록한다. */
    @Scheduled(
        fixedDelayString = "${storage.orphan-gc.interval:24h}",
        initialDelayString = "${storage.orphan-gc.initial-delay:1m}"
    )
    public void inspect() {
        // 1. 이전 전체 탐지가 끝나지 않았으면 같은 인스턴스의 중복 실행을 건너뛴다.
        if (!inspecting.compareAndSet(false, true)) {
            return;
        }
        try {
            // 2. 모든 목록·DB 비교가 끝난 결과만 정상 Snapshot으로 공개한다.
            StorageOrphanInspectionResult result = inspectionService.inspect();
            metrics.recordSuccess(result, clock.instant());
            log.info(
                "[STORAGE_ORPHAN] 탐지 완료: scanned={}, referenced={}, candidates={}, bytes={}, recent={}, invalid={}",
                result.scannedObjects(),
                result.referencedObjects(),
                result.orphanCandidates(),
                result.orphanCandidateBytes(),
                result.recentObjects(),
                result.invalidObjects()
            );
        } catch (RuntimeException exception) {
            // 3. 부분 결과를 버리고 실패만 기록해 다음 주기에 전체 탐지를 다시 시도한다.
            metrics.recordFailure();
            log.warn(
                "[STORAGE_ORPHAN] 탐지 실패: errorType={}",
                exception.getClass().getSimpleName()
            );
        } finally {
            // 4. 성공·실패와 관계없이 다음 Scheduler 호출이 진입할 수 있도록 Guard를 해제한다.
            inspecting.set(false);
        }
    }
}
