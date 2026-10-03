# guard422-delayed-start-01 — 독립 지연 복구 실측

- 시각: 2026-10-04 07:35:16–07:38:03 KST
- 위치: GCP 관리형 Workflows + 분리된 소형 시험 VM 1대
- 목적: 시작 명령을 보낸 노트북 세션이 끝난 뒤에도, 독립 실행이 지연 시각에 정확한 정지 VM을 시작하는지 확인
- 방법: 전용 서비스 계정에 시험 VM 한 대의 `compute.instances.get/start`만 조건부 부여 → VM `TERMINATED` 확인 → `gcloud workflows execute ... --data='{"delay_seconds":120}'` → 실행 `ACTIVE`/VM 상태 폴링
- 성공 기준: 예약 뒤 120초 이상 경과 시 VM `RUNNING`, Workflows `SUCCEEDED`, 결과 `STARTED_AND_RUNNING`

| KST | 안전하게 요약한 관측 | 해석 |
| --- | --- | --- |
| 07:35:16 | 실행 `ACTIVE`, 지연 120초 설정 | 클라이언트 명령은 즉시 반환; 타이머는 Workflows에 남음 |
| 07:35:32 | 게이트 `ARMED`, 잔여 104초 | 실행·workflow ID·시각을 실제 응답으로 검증. 이 짧은 리허설에서는 최소 잔여 60초 사용 |
| 07:37:22 | 대상 시험 VM `RUNNING` | 지연 후 시작 API가 실제 반영됨 |
| 07:38:03 | 실행 `SUCCEEDED`, `STARTED_AND_RUNNING` | 가드가 시작 후 VM 상태 재조회까지 통과 |

실행 식별자·내부 주소·서비스 계정 이메일은 공개 로그에 남기지 않았다. 상태 폴링 사이의 정확한 시작 순간은 계측하지 않았으므로 07:37:22는 **관측 시각**이다. 노트북 자체 전원 종료는 주입하지 않았고, 명령 프로세스 종료 후 클라우드 실행이 독립적으로 계속됨을 확인했다.
