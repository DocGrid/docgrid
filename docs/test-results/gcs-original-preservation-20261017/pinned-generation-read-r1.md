# GCS generation 지정 읽기 · R1

| 항목 | 값 |
| --- | --- |
| 목적 | 감사 목록에서 읽은 특정 generation의 객체를 CLI가 그대로 내려받는지 확인 |
| 실행 위치 | 로컬 임시 디렉터리에서 GCS 원본 버킷 객체 1건 읽기 |
| 방법 | 비공개 원장의 `source_generation`을 붙여 `gcloud storage cp <객체 URI>#<generation> <임시 파일> --quiet`, 크기·SHA-256 대조 |
| 실행 ID | `GCS-ORIGINAL-PINNED-READ-20261017-R1` |
| 성공 기준 | 읽기 성공, 크기·SHA-256 모두 이전 검증 원장과 일치 |
| 시각 | 2026-10-17 01:56 KST |

## 관측

객체 **1/1건** 읽기 성공, 크기·SHA-256 **1/1건** 일치. 이 회차는 generation 지정 다운로드 경로만 검증하며, 전체 감사 성공으로 해석하지 않는다. 객체 키·버킷명·바이트는 기록하지 않았다.
