# guard422-permission-denied-01 — 지연 시각 권한 상실 실측

- 시각: 2026-10-04 07:40:24–07:42:25 KST
- 위치: GCP 관리형 Workflows + 같은 분리 시험 VM
- 목적: 예약 당시에는 시작 권한이 있었지만 실제 지연 시각 전에 권한이 제거되면 실패를 드러내고 장애 주입을 차단하는지 확인
- 방법: 시험 VM `TERMINATED` → 120초 지연 실행 `ACTIVE` → 해당 VM의 전용 IAM 바인딩 1개 제거 → 종료 상태·VM 상태·게이트 재조회
- 성공 기준: Workflows `FAILED`·HTTP 403, VM 계속 `TERMINATED`, 게이트 `BLOCKED`

| KST | 안전하게 요약한 관측 | 해석 |
| --- | --- | --- |
| 07:40:24 | 실행 `ACTIVE`, 120초 지연 | 타이머 자체는 등록됨 |
| 07:40:35 전후 | 시험 VM에 한정된 IAM 바인딩 제거 | 다른 VM의 권한·상태는 변경하지 않음 |
| 07:42:25 | `read_before` 단계 HTTP 403, 실행 `FAILED` | 실제 실행 시각에는 읽기 권한이 없어 가드가 동작하지 못함 |
| 07:42:26 전후 | 대상 `TERMINATED`, 게이트 `BLOCKED`, 종료 코드 2 | 실패 실행을 장애 주입 승인으로 오인하지 않음 |

`FAILED`의 원본 응답에는 비공개 프로젝트·VM 식별자가 포함되어 공개본에 기록하지 않았다. 최초 CLI는 Python traceback에 로컬 경로를 출력해, 게이트가 이후 고정된 비식별 JSON `{"guard":"BLOCKED","reason":"GUARD_NOT_ACTIVE_OR_WRONG_WORKFLOW"}`만 출력하도록 수정하고 재검증했다. 이 시험은 권한 상실에 대한 실제 GCP 증거이며, 네트워크 단절·실제 노트북 전원 종료를 직접 주입한 증거는 아니다.
