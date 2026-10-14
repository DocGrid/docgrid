package com.opensource.docgrid.domain.document.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.VersionUploadRequestService.Candidate;
import com.opensource.docgrid.domain.document.service.VersionUploadRequestService.CleanupClaim;
import com.opensource.docgrid.domain.document.service.VersionUploadRequestService.CleanupTarget;
import com.opensource.docgrid.domain.document.storage.CandidateObjectSnapshot;
import com.opensource.docgrid.domain.document.storage.FileStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오래된 새 버전 후보를 조회하고 삭제가 명시적으로 켜졌을 때만 미참조 GCS 객체를 정리한다.
 * DB 예약으로 이전 업로더를 무효화한 다음 generation이 일치하는 객체만 삭제한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VersionUploadCandidateCleanupService {

    private final VersionUploadRequestService requestService;
    private final FileStorageService fileStorageService;

    /** 삭제가 꺼져 있으면 대상 수만 보고한다. 한 회차는 최대 100건으로 제한한다. */
    public CleanupResult reconcile(Duration gracePeriod, int limit, boolean deleteEnabled) {
        List<CleanupTarget> targets = requestService.findCleanupTargets(gracePeriod, limit);
        if (!deleteEnabled) {
            return new CleanupResult(targets.size(), 0, 0, 0, 0, 0, 0);
        }

        int reserved = 0;
        int deleted = 0;
        int absent = 0;
        int referenced = 0;
        int skipped = 0;
        int failed = 0;
        for (CleanupTarget target : targets) {
            try {
                // 1. 이전 업로더의 토큰을 DB에서 무효화한 뒤 외부 객체에 접근한다.
                CleanupClaim claim = requestService.reserveCleanup(target, gracePeriod);
                if (claim == null) {
                    continue;
                }
                reserved++;
                Candidate candidate = claim.target().candidate();
                boolean removeCandidate = false;
                try {
                    // 2. 이미 채택한 원본이나 GCS 외 후보는 삭제하지 않는다.
                    if (requestService.isReferenced(claim)) {
                        referenced++;
                        removeCandidate = true;
                    } else if (candidate.storedFile().storageProvider() != StorageProvider.GCS) {
                        skipped++;
                    } else {
                        Optional<CandidateObjectSnapshot> snapshot = fileStorageService.inspectCandidate(
                            candidate.storedFile()
                        );
                        if (snapshot.isEmpty()) {
                            // 이전 업로더의 지연 저장 가능성이 있으므로 위치 기록을 남긴다.
                            absent++;
                        } else if (matches(candidate, snapshot.get())
                            && fileStorageService.deleteCandidateIfGenerationMatches(
                                candidate.storedFile(), snapshot.get().generation()
                            )) {
                            deleted++;
                            removeCandidate = true;
                        } else {
                            skipped++;
                        }
                    }
                } finally {
                    // 3. 확인하거나 삭제하지 못한 후보는 다음 회차에 다시 대조한다.
                    requestService.finishCleanup(claim, removeCandidate);
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("새 버전 업로드 후보 정리 한 건에 실패했습니다. 오류종류={}",
                    exception.getClass().getSimpleName());
            }
        }
        return new CleanupResult(targets.size(), reserved, deleted, absent, referenced, skipped, failed);
    }

    private boolean matches(Candidate candidate, CandidateObjectSnapshot snapshot) {
        return snapshot.generation() != null
            && (candidate.generation() == null || candidate.generation().equals(snapshot.generation()))
            && candidate.fileSize() == snapshot.fileSize()
            && candidate.fileHash().equals(snapshot.sha256());
    }

    /** 한 회차의 개수만 보관하며 요청 키와 객체 위치는 로그에 포함하지 않는다. */
    public record CleanupResult(
        int found, int reserved, int deleted, int absent, int referenced, int skipped, int failed
    ) {
    }
}
