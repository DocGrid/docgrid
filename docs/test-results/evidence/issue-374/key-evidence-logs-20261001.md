# 핵심 근거 로그 — WebSocket 관리자 대시보드 부하 측정

관련 [측정 보고서](../../gimin-374-stomp-dashboard-outbound-load-20261001.md) · [PR #375](https://github.com/DocGrid/docgrid/pull/375) · [이슈 #374](https://github.com/DocGrid/docgrid/issues/374)

> **증거 범위:** 2026-10-01에 GCP 내부에서 실행한 기존 버전(`base`)과 보안 수정 버전(`fix`)의 합성 대시보드 push 시험이다. 이 문서는 남아 있는 원본에서 중요한 줄과 수치를 **선별**해 한눈에 볼 수 있게 만든 색인이다. 새로운 부하 시험이나 새로운 앱 로그가 아니다. 모든 시각은 별도 표기가 없으면 KST이며, JSONL과 JVM 덤프의 원래 시각은 UTC다.

| 증거 종류 | 공개 상태 | 정확히 무엇을 확인할 수 있나 | 확인할 수 없는 것 |
| --- | --- | --- | --- |
| [클라이언트 결과 JSON 12개](runs/) | 회차별 원본 | 연결·수신 건수, p50/p95/p99, 오류, 수신 sequence 중간 누락 | 실제 publisher 호출 횟수 전체와 운영 이벤트 빈도 |
| [앱 메트릭 JSONL 10개](metrics/) | 회차별 원본 | Hikari 대여·대기, CPU 표본, executor 대기열 | 개별 SQL 한 건의 정확한 실행 시간 |
| [DB 전후 스냅샷 4개](db/) | `server_ip` 제거 후 수치 보존 | primary 1·standby 2, 관측 창 전후 트랜잭션 수 | 동일 길이·동일 백그라운드 부하 창에서의 정밀 거래량 비교 |
| [JVM 전체 스레드 덤프](jvm-thread-dump-fix-n50-redacted.txt) | **1116줄 전체 내용**, PID·tid·nid·16진 주소를 가리고 끝의 빈 줄·공백 정리 | 한 시점의 브로커 → outbound 인가 → primary 조회 → PostgreSQL 응답 대기 경로 | 모든 메시지·모든 수신자의 지속 시간 |
| 앱 journal | 당시 실시간 수집기에서 선별·최대 500자로 절단된 행만 아래 기록 | 앱·DB 풀 기동, 세션 수 표본, 종료 | VM 삭제 후 전체 journal 복원, journal 행과 모든 개별 측정 회차의 정확한 매핑 |

## 1. 결과 원본: 50개 구독을 버전별 3회 반복

아래 값은 각 [실행 JSON](runs/)의 `clients_connected`, `frames_received`, `latency_ms.p95`, `client_errors`, `interior_sequence_gaps`에서 읽었다. p95 단위는 ms다.

| 파일 | 연결 | 30초 수신 프레임 | p95 | 오류 | 중간 sequence 누락 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `base-n50.json` | 50 | 5,000 | 9.117 | 0 | 0 |
| `base-n50-r2.json` | 50 | 5,000 | 8.475 | 0 | 0 |
| `base-n50-r3.json` | 50 | 5,000 | 8.011 | 0 | 0 |
| `fix-n50.json` | 50 | 603 | 2,386.041 | 0 | 0 |
| `fix-n50-r2.json` | 50 | 588 | 2,451.737 | 0 | 0 |
| `fix-n50-r3.json` | 50 | 588 | 2,443.748 | 0 | 0 |

**해석:** p95의 3회 중앙값은 **8.475 → 2,443.748 ms(약 288배)**이고, 전체 수신 프레임의 중앙값은 **5,000 → 588건(약 8.5배 차이)**다. “중간 누락 0건”은 이미 발행·수신한 sequence 사이에 구멍이 없다는 뜻이다. 동기 fan-out이 다음 발행을 늦춘 문제를 상쇄하지 않는다. 1·5·20개 구독 결과와 단일 실행의 한계는 [전체 보고서](../../gimin-374-stomp-dashboard-outbound-load-20261001.md)에 별도로 적었다.

## 2. 앱 메트릭 원본: DB 대여 증가, 풀·CPU·채널 비포화

아래는 각각 같은 번호의 [메트릭 JSONL](metrics/)에서 첫·마지막 `hikaricp_connections_usage_seconds_count` 차이와, 해당 수집 창의 최대·평균 표본을 계산한 값이다. 수집 창은 약 45초로 클라이언트 관측 30초보다 길다. 따라서 메시지당 정확한 대여 횟수로 읽으면 안 된다.

| 50명 회차 | 메트릭 표본 | Hikari 대여 증가 | pending 최대 | broker/outbound 대기열 최대 | 앱 CPU 평균 표본 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 기존 2회 | 23 | 50회 | 0 | 0 / 0 | 3.01% |
| 기존 3회 | 23 | 47회 | 0 | 0 / 0 | 2.24% |
| 수정 2회 | 23 | 914회 | 0 | 0 / 0 | 8.69% |
| 수정 3회 | 23 | 910회 | 0 | 0 / 0 | 5.32% |

**해석:** 수정 버전에서 DB 연결 대여가 크게 늘었지만, 이 표본에서 Hikari 대기와 broker/outbound 큐 적체는 모두 0이었다. CPU 평균도 포화 상태가 아니었다. 즉 “풀 크기 부족”이나 “CPU 부족”만으로 p95 약 2.4초를 설명하기 어렵다. 1회차 메트릭에는 큐 계측 시계열이 일부 없어 위 대기열 비교에서는 2·3회차만 사용했다.

## 3. JVM 원본: 브로커 호출 스레드가 PostgreSQL 응답을 기다림

50명 수정 버전 실행 중 `jcmd <시험 앱 PID> Thread.print -l`로 얻은 [비식별 JVM 전체 덤프](jvm-thread-dump-fix-n50-redacted.txt)는 **내용 1116줄**이다. 원본의 마지막 빈 줄과 줄 끝 공백을 정리했으며 호출 순서는 유지했다. PID·tid·nid·16진 JVM 주소를 가렸다. 다음은 [핵심 발췌](stack-excerpt-fix-n50.txt)의 호출 순서다.

```text
2026-10-01 12:30:28 UTC — MessageBroker-2
  sun.nio.ch.Net.poll
    → PostgreSQL QueryExecutorImpl.processResults
    → PgPreparedStatement.executeQuery
    → PrimaryRoleQueryService.findCurrentRoles
    → RoleAuthorityService.getRolesForAdmin
    → StompDashboardOutboundAuthorizationInterceptor.preSend
    → SimpleBrokerMessageHandler.sendMessageToSubscribers
    → DashboardBenchmarkPublisher.publish
```

**해석:** 합성 publisher가 메시지를 내보내는 동안 브로커의 수신자 전송 경로 안에서 primary 역할 확인 쿼리를 기다린다는 **호출 위치**를 보여준다. 한 시점의 스택은 “모든 요청이 똑같이 48ms 걸렸다”는 증거가 아니다. 그 시간 추정은 반복 부하·Hikari 표본과 함께 해석해야 한다.

## 4. DB 스냅샷 원본: primary 거래량 변화의 보조 근거

내부 IP 필드만 제거한 [스냅샷](db/)을 공개한다. 각 파일은 노드 3개의 `standby` 여부와 DB 지표를 가진다. 네 파일 모두 **primary 1·standby 2**였다.

| 관측 구간 | primary `xact_commit` 시작 → 종료 | 증가 | 해석 |
| --- | ---: | ---: | --- |
| 기존 50명 1회 주변 | 48,062 → 48,272 | +210 | 기준선의 DB 거래 증가 |
| 수정 50명 1회 주변 | 44,778 → 46,828 | +2,050 | 수정 버전의 DB 거래 증가가 더 큼 |

**한계:** 두 스냅샷 창은 정확히 같은 길이로 맞춰지지 않았고 백그라운드 거래도 섞인다. `+2,050/+210`을 **메시지당 SQL 배수**나 정확한 DB 처리량 비율로 사용하면 안 된다. 역할 조회가 DB에 더 많은 작업을 만들었다는 보조 증거다.

## 5. 앱 journal에서 남긴 중요 행

시험 당시 실시간 수집기는 앱 journal의 중요 항목만 골라 한국어 표로 저장했고, 각 행을 최대 500자로 절단했다. 다음 인용은 그 **선별 로그의 핵심 부분**이며 전체 journal 원문이 아니다. 일부 행의 버전·회차 매핑이 남아 있지 않아 같은 회차의 연속 로그로 주장하지 않는다.

| 시각(KST) | 선별된 원문 핵심 | 무엇을 확인했나 |
| --- | --- | --- |
| 21:20:48 | `HikariDataSource : docgrid-opensql-ha-pool - Start completed.` | 앱 DB 연결 풀 기동 |
| 21:21:08 | `DocgridApplication : Started DocgridApplication in 44.609 seconds` | 앱 요청 수신 준비 |
| 21:37:15 | `WebSocketSession[0 current ..., 1 total, 0 closed abnormally ...]` | 적어도 한 세션 연결이 처리된 표본 |
| 21:46:19 | `WebSocketSession[50 current WS(50) ..., 50 total, 0 closed abnormally ...]` | 50개 동시 WebSocket 세션 표본 |
| 21:51:59 | `HikariDataSource : docgrid-opensql-ha-pool - Shutdown completed.` | 시험 앱 종료 시 DB 풀 정리 |

전체 선별 행은 [측정 보고서의 실시간 로그 표](../../gimin-374-stomp-dashboard-outbound-load-20261001.md)에 있다. 앱 VM을 삭제했으므로 전체 journal을 지금 다시 가져올 수 없다. 위에서 인용하지 않은 오류가 전혀 없었다고 주장하지 않는다.

## 측정 결과로 말할 수 있는 결론

> 관리자 권한 회수 직후의 WebSocket 데이터 노출을 막기 위해 수신자별 최신 primary 역할 조회를 적용했다. 실제 OpenProxy·OpenSQL 경유 50개 구독을 버전별 3회 측정한 결과, p95가 8.5ms에서 2.44초로 늘고 수신 프레임 중앙값이 5,000건에서 588건으로 줄었다. JVM 스택·Hikari 대여량·CPU·채널 지표를 대조해 브로커 fan-out 안의 동기 DB 확인이 병목임을 좁혔다. 따라서 보안 수정과 성능 비용을 별도 근거로 제시하고, 다음 최적화에서 두 조건을 함께 재검증하기로 했다.

이는 이번 환경과 합성 부하에서의 **관측·해석**이다. 실제 이벤트 빈도, 다중 백엔드, 권한 회수와 고부하의 동시 시험, 장애 상황까지 검증했다는 문장으로 확장해서는 안 된다.
