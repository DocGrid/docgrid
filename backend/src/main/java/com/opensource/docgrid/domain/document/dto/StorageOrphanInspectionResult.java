package com.opensource.docgrid.domain.document.dto;

/**
 * 저장소 고아 Object 한 번의 전체 탐지 결과를 전달하는 불변 Snapshot이다.
 * 삭제 결과는 포함하지 않아 1단계 탐지 경계를 명확히 유지한다.
 */
public record StorageOrphanInspectionResult(
    long scannedObjects,
    long referencedObjects,
    long orphanCandidates,
    long orphanCandidateBytes,
    long recentObjects,
    long invalidObjects
) {
}
