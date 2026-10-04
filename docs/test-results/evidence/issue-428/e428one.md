# 실행 기록: `e428one` etcd 한 멤버 일시정지

- 실행 위치: node1 VM에서 SQL probe, node3 VM에서 etcd PID `SIGSTOP/SIGCONT`.
- 시각: 2026-10-04 **05:47:53.757~05:49:14.129 UTC**.
- 방법: `etcd_write_probe.sh e428one 70`; node3의 `etcd_fault_guard.sh arm → pause → recover → cancel`.
- 성공 조건: 2/3 정족수 중 커밋 지속, 모든 응답과 DB 행 대조, 재개 후 etcd 3/3.

| UTC 시각·단계 | 결과 요약 | 해석 |
| --- | --- | --- |
| `05:48:16.740` node3 etcd 중단 | 호스트 가드 armed; etcd 프로세스 `T` 상태 확인 | Patroni·PostgreSQL·VM은 그대로 실행 |
| 중단 구간의 안전 이벤트 | **48/48건 committed**, 실패·결과 불명 **0건** | 단일 멤버 부재 중 DB 직접 쓰기 지속 |
| `05:49:13.011` node3 etcd 재개 | 프로세스 running, 타이머 해제 | 약 56초의 제한된 장애 종료 |
| 전체 실행·DB 대조 | **70/70건 committed**, DB 행 **70**·고유 ID **70** | 응답 누락·중복 관측 0 |
| 복구 확인 | 세 etcd endpoint 모두 health `true` | 3/3 복귀 |

허용 필드만 남긴 probe 출력 일부(원문에 암호·내부 주소 없음):

```text
run_id=e428one utc=2026-10-04T05:48:38.714Z sample=40 outcome=committed
run_id=e428one utc=2026-10-04T05:49:13.123Z sample=70 outcome=committed
run_id=e428one event=finished utc=2026-10-04T05:49:14.129Z passed=70 failed_or_unknown=0
run_id=e428one in_recovery=f rows=70 distinct_request_ids=70
```

부하량은 약 1건/초의 로컬 SQL이며, 앱·프록시를 거친 HTTP 처리량이나 p95를 측정한 것이 아니다.
