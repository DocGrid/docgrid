package com.opensource.docgrid.domain.document.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService.Candidate;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService.CleanupClaim;
import com.opensource.docgrid.domain.document.service.InitialUploadRequestService.CleanupTarget;
import com.opensource.docgrid.domain.document.storage.CandidateObjectSnapshot;
import com.opensource.docgrid.domain.document.storage.FileStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오래된 최초 업로드 후보를 조회하고, 명시적으로 켠 경우에만 미참조 GCS 객체를 조건부 삭제한다.
 * DB 예약을 먼저 커밋해 늦게 끝난 업로더의 문서 생성과 외부 객체 삭제가 겹치지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitialUploadCandidateCleanupService {

    private final InitialUploadRequestService requestService;
    private final FileStorageService fileStorageService;

    /** 삭제가 꺼져 있으면 대상 수만 보고한다. 실행은 최대 100건으로 제한한다. */
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
                // 1. 이전 업로더의 토큰을 무효화하고 예약을 커밋한다. 경합에서 진 대상은 건너뛴다.
                CleanupClaim claim = requestService.reserveCleanup(target, gracePeriod);
                if (claim == null) {
                    continue;
                }
                reserved++;
                Candidate candidate = claim.target().candidate();
                boolean removeCandidate = false;
                try {
                    // 2. DB가 참조하는 원본은 외부 저장소에 손대지 않고 후보 표시만 제거한다.
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
                            // 오래 지연된 이전 업로더가 아직 생성 중일 수 있어 위치 기록은 보존한다.
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
                    // 3. 실패 시 후보를 남겨 다음 회차가 다시 대조하도록 한다.
                    requestService.finishCleanup(claim, removeCandidate);
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("초기 업로드 후보 정리 한 건에 실패했습니다. 오류종류={}",
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

    /** 한 회차의 집계만 담는다. 요청 키·버킷·객체 위치는 외부로 내보내지 않는다. */
    public record CleanupResult(
        int found, int reserved, int deleted, int absent, int referenced, int skipped, int failed
    ) {
    }
}
