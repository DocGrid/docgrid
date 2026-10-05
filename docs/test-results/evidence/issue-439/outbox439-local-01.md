# Outbox 단위 기준선 실행 로그

- 실행 ID: `outbox439-local-01`
- 실행 시각: 2026-10-05 20:54:02–20:54:16 KST
- 환경·리비전: 로컬 JVM, `750b0887c5bf02fc0fd862eb224577eccb435ae1`
- 목적: Writer, Claim, Dispatch, 실패·Lease 복구, 버전 Handler의 기존 단위 계약 확인
- 성공 기준: 선택한 테스트 전부 통과, 실패·건너뜀 0
- 방법: `./backend/gradlew -p backend test --tests '*SyncEventWriterTest' --tests '*SyncEventClaimServiceTest' --tests '*SyncEventDispatchServiceTest' --tests '*SyncEventFailureServiceTest' --tests '*SyncEventLeaseRecoveryServiceTest' --tests '*DocumentVersionSyncEventHandlerTest' --rerun-tasks --console=plain --no-daemon`

| 관측 | 숫자·결과 | 해석 |
| --- | --- | --- |
| 선택한 단위 테스트 | **11/11 통과**, 실패 0, 건너뜀 0 | 로컬 코드 경계는 통과 |
| 실제 GCP DB·Dispatcher | **0건 실행** | GCP 소비나 장애 수렴 결과가 아님 |

경고·오류: 없음. 재시도: 없음. 정리: 추가 VM 또는 컨테이너를 만들지 않았다. 원본 콘솔 출력은 실행별 로컬 비공개 로그에 보존했고, 이 파일에는 식별자와 비밀을 제거한 숫자 요약만 남겼다.
