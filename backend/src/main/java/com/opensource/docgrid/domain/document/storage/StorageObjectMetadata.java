package com.opensource.docgrid.domain.document.storage;

import java.time.Instant;

/**
 * 파일 저장소 목록 조회에서 얻은 Object 위치·크기·최종 수정 시각의 불변 Snapshot이다.
 *
 * <p>고아 Object 탐지는 파일 본문을 읽지 않고 이 Metadata와 DB 참조만 비교한다. Provider가
 * 수정 시각을 제공하지 못할 수 있으므로 {@code lastModified}는 비어 있을 수 있고, 이 경우
 * 상위 계층은 안전하게 자동 판정 대상에서 제외한다.
 */
public record StorageObjectMetadata(
    StoredFile storedFile,
    long size,
    Instant lastModified
) {
}
