# 재가동·백업 R2

- **Run ID**: `DR-RESTART-BACKUP-20261015-R2`
- **시각**: 2026-10-15 17:00~19:05 KST; 단계별 초 단위 로그는 미수집
- **실행 위치**: GCP OpenSQL 3노드·내부 전용 백업 VM
- **목적·방법**: 사용자 요청으로 한 번 정지한 네 VM을 재가동한 뒤 Patroni API와 Barman `check`, 슬롯·수신을 다시 확인
- **성공 기준**: 리더 1·스트리밍 Standby 2, Barman 22 OK·0 FAILED

## 관측

| 단계 | 관측 |
| --- | --- |
| 네 VM 재시작 | DB 3대·백업 1대 `RUNNING` |
| Patroni 첫 조회 | 리더 1·스트리밍 Standby 2 |
| Barman 첫 검사 | 20 OK·2 FAILED: 복제 슬롯, `receive-wal running` |
| 슬롯 생성·`barman cron` 실행 | 명령 종료 코드 0; 즉시 검사에는 비동기 반영 전의 2개 실패가 남음 |
| 후속 Barman 검사 | 22 OK·0 FAILED |
| 최종 확인 | 리더 1·스트리밍 Standby 2, Barman 22 OK·0 FAILED, 네 VM `RUNNING` |

**판정**: 재시작 후에도 슬롯·WAL 수신 확인이 필요했다. 후속 검사는 통과했지만 다음 리더 교체 때 자동 복구된다는 증거는 없다.
