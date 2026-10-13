package com.opensource.docgrid.domain.document.storage;

/** 복구 후보의 실제 크기·SHA-256과 조건부 삭제에 사용할 GCS generation을 담는다. */
public record CandidateObjectSnapshot(long fileSize, String sha256, Long generation) {
}
