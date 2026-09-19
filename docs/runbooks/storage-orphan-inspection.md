# 저장소 고아 Object 탐지 Runbook

## 경보 의미

`DocGridStorageOrphanInspectionFailed`는 최근 30시간 동안 Local·MinIO·S3 목록 조회 또는
`file_objects` 참조 비교가 한 번 이상 실패했다는 뜻이다. `DocGridStorageOrphanInspectionStale`은
마지막으로 완료된 전체 탐지 결과가 30시간 넘게 갱신되지 않았다는 뜻이다.

탐지는 기본적으로 꺼져 있다. `STORAGE_ORPHAN_GC_ENABLED=true`인 Backend에만 관련 Metric이 생기며,
현재 단계는 후보 개수와 용량만 집계하고 Object를 삭제하지 않는다.

## 확인 순서

1. Backend 로그에서 `[STORAGE_ORPHAN] 탐지 실패`의 예외 유형과 같은 시각의 저장소·DB 오류를 확인한다.
2. `/actuator/prometheus`에서 다음 Metric을 확인한다.
   - `docgrid_storage_orphan_inspections_total{outcome="success|failed"}`
   - `docgrid_storage_orphan_snapshot_age_seconds`
   - `docgrid_storage_orphan_candidates`
   - `docgrid_storage_orphan_bytes`
   - `docgrid_storage_orphan_invalid_objects`
3. 활성 `STORAGE_TYPE`과 `STORAGE_BUCKET`이 Object가 실제 저장된 위치와 같은지 확인한다.
4. 저장소 목록 권한과 DB 연결이 복구되면 다음 주기에서 성공 Counter와 Snapshot 시각이 갱신되는지 확인한다.

## 후보 수치 해석

후보는 활성 Provider·Bucket의 `documents/` 아래에서 정해진 UUID Key 형식과 유예 시간을 통과하고,
같은 `(storage_provider, bucket_name, object_key)`의 `file_objects` 행이 없는 Object다. 최근 Object,
형식이 다른 Object, 최종 수정 시각이 없는 Object는 삭제 대상으로 판단하지 않는다.

후보가 발견되어도 이 단계에서는 수동 삭제하지 않는다. 저장소와 DB Snapshot을 별도로 보존해 원인을 확인하고,
후속 삭제 단계에서 재검증과 실행 이력을 갖춘 뒤 정리한다.
