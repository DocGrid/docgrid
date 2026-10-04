# 실행 기록: `e428quorum` 지속 정족수 상실

- 실행 위치: node1 VM SQL probe, node2·3 VM etcd PID `SIGSTOP/SIGCONT`.
- 시각: 2026-10-04 **05:57:18.422~05:58:21.458 UTC**.
- 방법: node2·3의 독립 120초 가드가 armed인 상태에서 `etcd_write_probe.sh e428quorum 55`; 나중에 두 PID를 재개.
- 성공 기준: `ttl=30s` 이후에도 관측된 DB 직접 쓰기를 DB 행과 대조하고, 복구 뒤 단일 primary·etcd 3/3 확인.

| UTC 시각·단계 | 결과 요약 | 해석 |
| --- | --- | --- |
| `05:56:29.678` | 두 번째 etcd PID 일시정지 | 3멤버 중 2개가 응답 불가 |
| `05:56:59.678` | 정족수 상실 뒤 30초 경과 | Patroni의 관측 `ttl=30s`를 넘는 구간 시작 |
| `05:57:18.422` probe 시작 | 첫 표본부터 TTL 초과 | 준비 실패한 이전 실행과 독립된 run ID |
| `05:57:51.958` 30번째 표본 | 이 시점까지 수동 복구 전인 **30/30건 committed**, DB **30행·고유 ID 30** | 최소 30건의 명확한 failsafe 구간 쓰기 증거 |
| 이후 node2·3 재개 | etcd 각 PID running, 자동 재개 가드 해제 | 복구의 정확한 서버 시각은 별도 보존하지 못함 |
| `05:58:21.458` probe 종료 | 전체 **55/55건 committed**, DB **55행·고유 ID 55** | 전체 실행 누락·중복 관측 0. 55건 모두를 장애 중 처리로 분류하지 않음 |
| 같은 구간 Patroni 파일 로그 | DCS 관련 **12행**, `failsafe` 언급 **9행** (집계만) | 설치 빌드에서 failsafe 경로가 보고됨. 원문은 내부 식별자 노출 위험으로 공유하지 않음 |
| 종료 후 | etcd **3/3**, primary **1**, streaming replica **2**, LB HEALTHY **2/2** | 복구 상태 확인. 장애 중 연속적인 역할 감시는 하지 않음 |

허용 필드만 남긴 probe 출력 일부(원문에 암호·내부 주소 없음):

```text
run_id=e428quorum utc=2026-10-04T05:57:51.958Z sample=30 outcome=committed
run_id=e428quorum utc=2026-10-04T05:58:20.454Z sample=55 outcome=committed
run_id=e428quorum event=finished utc=2026-10-04T05:58:21.458Z passed=55 failed_or_unknown=0
run_id=e428quorum in_recovery=f rows=55 distinct_request_ids=55
```

`etcdctl endpoint health`의 **장애 중 출력 자체는 캡처하지 않았다**. 정족수 1/3은 두 PID의 `T` 상태와 원래 3멤버 구성을 합쳐 판단한 것이다. SQL probe는 primary 컨테이너 내부 경로라 HTTP 상태 코드·외부 클라이언트 RTO를 제공하지 않는다.
