# 실행 기록: `e428smoke` 정상 DB probe

- 실행 위치: node1 VM 호스트 → 로컬 DB 컨테이너.
- 시각: 2026-10-04 **05:47:17.818~05:47:21.300 UTC**.
- 방법: `etcd_write_probe.sh e428smoke 3`; 각 표본은 고유 synthetic `request_id`로 한 건의 autocommit SQL INSERT.
- 성공 조건: 커밋 3/3, DB 행 3·고유 ID 3, 실패·결과 불명 0.

| 관측 | 결과 요약 | 해석 |
| --- | --- | --- |
| 1초 간격 안전 이벤트 | 표본 1·2·3 모두 `committed` | 쓰기 probe의 정상 경로 확인 |
| `etcd_probe_reconcile.sh e428smoke` | `in_recovery=f`, 행 **3**, 고유 ID **3** | 3개의 응답과 DB 결과 일치 |
| 실패·타임아웃 | **0건** | 장애 관측의 기준선. HTTP 정상 기준선은 아님 |

DB 암호는 컨테이너 내부 자격 파일에서만 읽고 명령·로그·채팅에 값으로 출력하지 않았다.
