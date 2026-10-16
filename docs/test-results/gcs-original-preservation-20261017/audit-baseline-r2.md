# GCS 원본 보존 기준선 감사 · AUDIT-BASELINE-R2

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 로컬 다중 프로세스 제한을 피한 뒤 기존 사본의 새 원본 누락 여부 확인 |
| 실행 위치 | 로컬 격리 임시 디렉터리에서 비공개 GCS 원본·사본을 읽기 전용 조회 |
| 명령 | `python3 scripts/opensql/verify_gcs_original_preservation.py --config <비공개 설정>` (`gsutil rsync` 단일 프로세스) |
| 실행 ID | `GCS-ORIGINAL-AUDIT-BASELINE-20261017-R2` |
| 성공 기준 | 비교가 실행되어 누락이면 `missing_copy`, 모두 같으면 성공 건수 기록; 버킷명·키·원본 바이트 미공개 |

## 관측 결과

2026-10-17 01:23 KST에 실행했습니다. `gsutil rsync`가 로컬 대상 디렉터리를 미리 생성해야 한다는 조건 때문에 `source_download`에서 실패했습니다. 추가 읽기 전용 진단에서 다운로드 파일 0건과 해당 도구의 경로 조건을 확인했습니다. 객체 비교 결과는 얻지 못했습니다. 대상 디렉터리를 생성하도록 수정하고 R3로 재실행합니다.
