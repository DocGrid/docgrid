package com.opensource.docgrid.domain.document.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 새 버전 업로드의 오래된 후보를 주기적으로 집계한다. 외부 객체 삭제는 별도 설정을 켰을 때만 수행한다.
 * 여러 앱 인스턴스가 실행해도 DB 예약 토큰으로 각 후보의 삭제 소유권을 한정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "document.upload", name = "candidate-reconcile-enabled",
    havingValue = "true", matchIfMissing = true)
public class VersionUploadCandidateCleanupScheduler {

    private final VersionUploadCandidateCleanupService cleanupService;

    @Value("${document.upload.candidate-delete-enabled:false}")
    private boolean deleteEnabled;

    @Value("${document.upload.candidate-grace-hours:24}")
    private long graceHours;

    /** 기본값은 한 시간마다 24시간 지난 후보 100건을 조회만 한다. */
    @Scheduled(fixedDelayString = "${document.upload.candidate-reconcile-interval-ms:3600000}")
    public void reconcile() {
        VersionUploadCandidateCleanupService.CleanupResult result = cleanupService.reconcile(
            Duration.ofHours(graceHours), 100, deleteEnabled
        );
        if (result.found() > 0 || result.failed() > 0) {
            log.info("새 버전 업로드 후보 정리: 삭제활성={}, 발견={}, 예약={}, 삭제={}, 부재={}, 참조={}, 보류={}, 실패={}",
                deleteEnabled, result.found(), result.reserved(), result.deleted(), result.absent(),
                result.referenced(), result.skipped(), result.failed());
        }
    }
}
