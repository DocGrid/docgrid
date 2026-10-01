# 관리자 WebSocket 수신자별 권한 검증 부하 측정 — 2026-10-01

관련 이슈: [#374](https://github.com/DocGrid/docgrid/issues/374) · 선행 보안 수정: [#373](https://github.com/DocGrid/docgrid/pull/373)

> 상태: **2026-10-01에 수행한 시험의 사후 증거 정리**다. 시험 중에는 백엔드 journal을 실시간 수집해 한국어로 분류했고, 종료 후 수집기를 멈췄다. 시각은 한국시간(KST)이며, 공개본에는 계정·토큰·암호·내부 주소를 남기지 않는다. 아래 표는 회차별 JSON/JSONL과 당시 JVM 덤프를 대조해 작성했다. 이 PR에서 새 클라우드 부하 시험을 실행한 것은 아니다.

## 시험 대상과 해석 범위

| 항목 | 설정·의미 |
| --- | --- |
| 대상 | 임시 백엔드 VM 1대, 임시 부하 발생기 VM 1대, 기존 OpenSQL 3노드(주 노드 1·대기 노드 2) |
| 요청 경로 | 부하 VM → WebSocket/STOMP → 백엔드의 대시보드 구독 → OpenProxy → 현재 primary 역할 조회 |
| 실행 코드 | [PR #373](https://github.com/DocGrid/docgrid/pull/373)의 `b425c3535194be6719b75ef79cf5100d9a86be97`와 비교 기준 `f6213bbdb6f487af00727be156da0710d619cc6a`. 양쪽에 동일한 임시 `wsbench` 프로필의 고정 주기 publisher·일회용 ADMIN 계정만 더해 빌드. 임시 Java fixture는 안전상 저장소에 넣지 않았다. 실행 당시의 Python 측정 코드에서 예외 문자열만 비식별형으로 바꾼 사본을 `scripts/opensql/websocket_dashboard_*.py`에 보존한다. |
| 부하 | 구독자 수 1·5·20·50. 합성 대시보드 메시지를 `fixedRate=300 ms`로 시도. 각 단계 10초 예열·30초 관측(단일 구독 스모크는 3초·12초). 50개는 버전별 3회 반복 |
| 분리한 비용 | 대시보드 집계 SQL·PDF 인덱싱 비용은 제외. 수신자별 outbound 권한 재검증과 전송 비용을 측정. 모든 구독은 같은 일회용 ADMIN 사용자로 로그인 |
| 지연 측정 | publisher가 넣은 시각부터 부하 VM 수신까지. 두 VM을 동일 Google metadata NTP 기준으로 재동기화한 뒤의 결과만 유효하게 사용. 시험 종료 표본의 시간 오차는 각각 약 ±0.6 ms |
| 환경 범위 | 임시 앱·부하 VM은 Rocky Linux 9.8 x86_64, 각 2 vCPU. 기존 DB 3노드는 Rocky Linux 9.7 x86_64. 임시 VM 1대의 백엔드만 측정했으며 2백엔드 로드밸런싱은 이 시험에 포함하지 않음 |

원본 결과는 [`evidence/issue-374/runs/`](evidence/issue-374/runs/)에 **조건·회차별 JSON 12개**, 앱 자원 지표는 [`evidence/issue-374/metrics/`](evidence/issue-374/metrics/)에 **JSONL 10개**로 나눠 보존했다. 단일 구독 스모크 2회에는 앱 메트릭 파일이 없다. 파일명·출처·누락 범위는 [증거 목록](evidence/issue-374/README.md), 공개 전 검사 결과는 [증거 검증 로그](evidence/issue-374/artifact-validation-20261001.md)에 적었다. 원본 DB snapshot의 내부 IP 필드를 제거한 [전후 스냅샷 4개](evidence/issue-374/db/)와 JVM 주소·ID를 가리고 끝 공백을 정리한 [전체 스레드 덤프 1개](evidence/issue-374/jvm-thread-dump-fix-n50-redacted.txt)도 공개했다. 중요한 수치·로그·한계는 [핵심 근거 로그](evidence/issue-374/key-evidence-logs-20261001.md)에 모았다. 앱 journal의 전체 원본은 임시 VM 삭제 후 복원할 수 없어 아래 선별 로그만 남아 있다.

## 먼저 읽을 결론: 구독자가 늘어날수록 동기 조회 비용이 커졌다

| 구독자 | 관측 시간 | 기존 버전: 수신 건수 / p95 | 보안 수정 버전: 수신 건수 / p95 | 해석 |
| ---: | ---: | ---: | ---: | --- |
| 1 | 12초 | 40건 / 2.9 ms | 40건 / 56.0 ms | 연결·전송은 성공하지만 조회 지연이 추가됨 |
| 5 | 30초 | 500건 / 3.6 ms | 500건 / 217.5 ms | 예정 push 수는 유지, 전달 지연 상승 |
| 20 | 30초 | 2,000건 / 5.2 ms | 599건 / 969.3 ms | publisher의 동기 전송이 느려져 push 생성량 감소 |
| 50 | 30초, 버전별 3회 중앙값 | 5,000건 / 8.5 ms | 588건 / 2,443.7 ms | p95 약 288배, 수신 건수 약 8.5배 차이 |

위 p95는 **수신 프레임 지연**의 분위수이며, 50개 행만 3회 반복의 중앙값이다. 1·5·20개 행은 각 버전 1회여서 분산이나 장기 안정성을 말할 수 없다. 수신 건수 감소는 수신 도중의 중간 sequence 누락과 다르다. 동기 `convertAndSend`가 다음 발행을 늦춘 결과로 해석한다.

## 진행 현황 및 실측

| 상태 | 단계 | 수신 프레임(전체) | 수신 지연 p50 / p95 / p99 | 오류·중간 sequence 누락 | 즉시 해석 |
| --- | ---: | ---: | --- | --- | --- |
| 완료 | 단일 구독 재측정(12초) | 40 | 14.3 / 56.0 / 57.8 ms | 0 / 0 | 기본 연결·인증·전송 정상. 앞선 음수 지연 2회는 NTP 수렴 전 값이므로 폐기 |
| 완료 | 5개 구독(30초) | 500 | 113.4 / 217.5 / 261.3 ms | 0 / 0 | 수신자별 확인이 지연을 키우기 시작함 |
| 완료 | 20개 구독(30초) | 599 | 514.3 / 969.3 / 1019.0 ms | 0 / 0 | Hikari 대기 최대 0·앱 CPU 최대 표본 약 14.6%로, 단순 풀/CPU 포화만은 아님 |
| 완료 | 50개 구독(30초, 첫 실행) | 603 | 1255.1 / 2386.0 / 2484.9 ms | 0 / 0 | 전달량이 구독자 증가에 비례하지 않고 p95가 2초를 넘음 |

## 관측된 앱 내부 경로

```text
합성 대시보드 publisher
  → Spring SimpleBroker가 구독자마다 outbound MESSAGE 생성
  → StompDashboardOutboundAuthorizationInterceptor.preSend
  → RoleAuthorityService.getRolesForAdmin
  → PrimaryRoleQueryService.findCurrentRoles (주 노드 확인 + 역할 조회)
  → OpenProxy → OpenSQL primary
  → ADMIN이면 해당 세션으로 전송
```

50개 구독 시험 중 수집한 [전체 JVM 덤프 비식별본](evidence/issue-374/jvm-thread-dump-fix-n50-redacted.txt)과 [핵심 스택 발췌](evidence/issue-374/stack-excerpt-fix-n50.txt)에서 `MessageBroker` 스레드가 `SimpleBrokerMessageHandler.sendMessageToSubscribers`의 구독자 반복문 안에서 위 역할 조회를 실행하며 PostgreSQL 응답을 기다리는 모습을 확인했다. 이는 **동기 DB 확인이 브로커 전송 경로에 있다**는 직접 증거다. [수신자별 차단 코드](https://github.com/DocGrid/docgrid/blob/b425c3535194be6719b75ef79cf5100d9a86be97/backend/src/main/java/com/opensource/docgrid/domain/auth/websocket/StompDashboardOutboundAuthorizationInterceptor.java#L32-L62)는 캐시를 거치지 않는 [primary 역할 조회](https://github.com/DocGrid/docgrid/blob/b425c3535194be6719b75ef79cf5100d9a86be97/backend/src/main/java/com/opensource/docgrid/domain/auth/service/query/PrimaryRoleQueryService.java#L27-L38)를 매 수신자에게 실행한다. 이 한 시점의 덤프만으로 개별 쿼리 지연이나 모든 수신자의 동일한 실행 시간을 증명하지는 않는다.

## 50개 동시 구독: 전후 3회 반복 결과

| 실행 | 30초 수신 프레임 | 수신 지연 p50 | p95 | p99 | 연결/오류/중간 sequence 누락 |
| --- | ---: | ---: | ---: | ---: | --- |
| 기준선 1회 | 5,000 | 7.0 ms | 9.1 ms | 10.1 ms | 50/0/0 |
| 기준선 2회 | 5,000 | 7.0 ms | 8.5 ms | 9.1 ms | 50/0/0 |
| 기준선 3회 | 5,000 | 6.4 ms | 8.0 ms | 14.1 ms | 50/0/0 |
| PR 버전 1회 | 603 | 1,255.1 ms | 2,386.0 ms | 2,484.9 ms | 50/0/0 |
| PR 버전 2회 | 588 | 1,313.9 ms | 2,451.7 ms | 2,569.3 ms | 50/0/0 |
| PR 버전 3회 | 588 | 1,291.1 ms | 2,443.7 ms | 2,554.4 ms | 50/0/0 |

50개 구독에서 각 실행의 p95 중앙값은 **기준선 8.5 ms → PR 버전 2,443.7 ms(약 288배)**다. 30초 전체 수신 프레임의 중앙값은 **5,000 → 588(약 8.5배 감소)**다. 이를 구독자당 환산하면 약 **3.33건/초 → 0.39건/초**다. 이는 3회 반복의 실측값이며, 다른 하드웨어·장기 운영으로 일반화한 보장값은 아니다.

```text
50개 구독의 실제 팬아웃 경로

시험 publisher: 약 300 ms마다 /topic/dashboard 발행을 시도
        │
        ▼
SimpMessagingTemplate.convertAndSend
        │  이 호출이 끝날 때까지 publisher 실행도 끝나지 않음
        ▼
SimpleBrokerMessageHandler.sendMessageToSubscribers
        ├─ 세션 1 → outbound interceptor → primary 확인·역할 조회 → 전송
        ├─ 세션 2 → outbound interceptor → primary 확인·역할 조회 → 전송
        ├─ ...
        └─ 세션 50 → outbound interceptor → primary 확인·역할 조회 → 전송
        │
        ▼
그제야 다음 합성 push를 시작할 수 있음

실측: PR 버전의 50개 수신 지연 p95 ≈ 2.4초
      기준선은 세션별 DB 조회가 없어 p95 ≈ 8.5 ms
```

이 흐름은 JVM 스레드 덤프의 `DashboardBenchmarkPublisher.publish → SimpMessagingTemplate.convertAndSend → SimpleBrokerMessageHandler.sendMessageToSubscribers → StompDashboardOutboundAuthorizationInterceptor.preSend → PrimaryRoleQueryService.findCurrentRoles → PgPreparedStatement.executeQuery` 경로로 확인했다. 따라서 “브로커 큐가 쌓여 느려졌다”는 해석은 틀리다. 반복 실행 2·3회차의 `brokerChannelExecutor`와 `clientOutboundChannelExecutor` 큐 최대값은 모두 **0**이었다. 실제로는 **호출 스레드가 순차 DB 조회에서 막혀 publisher 자체가 느려졌다**.

```text
한 번의 합성 push가 차지한 시간(설명용 축, 값은 관측치 근사)

기준선, 구독 50개:
  시작 ├─ 브로커 팬아웃·전송 ─┤ 종료
       0                     약 8.5 ms (수신 p95 중앙값)

PR 버전, 구독 50개:
  시작 ├─ 세션별 primary 트랜잭션 약 45~50 ms × 50 ─┤ 종료
       0                                            약 2.44 s (수신 p95 중앙값)

실측 풀 사용 합계/대여 횟수(첫 50개 실행): 41.6 s / 860회 ≈ 48 ms/대여
N=5·20·50의 수신 p95/N: 약 44·48·48 ms
→ 한 번의 DB 확인 비용이 구독자 수만큼 직렬로 누적된다는 해석과 일치.
   단, 풀에는 백그라운드 작업도 포함되므로 48 ms를 개별 권한 쿼리의 정밀값으로 단정하지 않음.
```

## 자원·DB 대조와 판정

| 관측 항목 | 기준선 | PR 버전 | 해석 |
| --- | ---: | ---: | --- |
| 50개 구독 2·3회차 Hikari 대여 증가(각 약 45초) | 50·47회 | 914·910회 | 수신자별 최신 역할 조회로 DB 연결 대여가 크게 증가 |
| Hikari 대기 최대값 | 0 | 0 | 커넥션 풀 5개가 고갈된 상황은 아님 |
| Hikari 활성 연결 최대값 | 1 | 1·3 | 한 호출 경로의 직렬 대기가 핵심이며, 3은 다른 백그라운드 작업이 겹친 순간의 최대 표본 |
| 앱 `process_cpu_usage` 평균 표본(2·3회차) | 약 3.0%·2.2% | 약 8.7%·5.3% | CPU 포화로 p95 2.4초를 설명하기 어려움 |
| 브로커·outbound executor 대기열 최대 | 0·0 | 0·0 | 비동기 큐 적체가 아니라 `convertAndSend` 호출의 동기 지연 |
| primary `pg_stat_database.xact_commit` 증가(첫 50개 실행 주변) | 210 | 2,050 | DB 쪽 거래량 증가를 보강하는 증거. 관측 창이 완전히 동일하지 않고 백그라운드 거래도 포함하므로 정확한 메시지당 거래 수는 아님 |
| 앱 DB 소켓 | PR 버전과 같은 JDBC 설정 | OpenProxy 두 주소의 6432 포트로 연결된 소켓 5개를 직접 관측 | 앱이 PostgreSQL 5432에 직결한 시험이 아님. 기준선 소켓은 별도 `ss`로 기록하지 않았음 |
| DB 역할 | primary 1·standby 2 | primary 1·standby 2 | 이번 시험은 장애 전환 시험이 아니며 역할 변화 없음 |

```text
관측으로 좁힌 병목 위치

50개 세션에서 p95 ≈ 2.4초
       │
       ├─ 앱 CPU 포화?             아니오: 평균 표본 5~9% 범위
       ├─ Hikari 풀 고갈?          아니오: pending 최대 0, 풀 최대 5
       ├─ outbound 작업 큐 적체?  아니오: queued 최대 0
       └─ 동기 팬아웃 중 DB 대기?   예: JVM 스택이 PG 응답 대기를 직접 가리킴
                                    Hikari 대여 횟수도 약 18~19배 증가

결론: 보안상 매 수신자 재검증은 작동하지만,
      그 검증을 브로커의 실시간 전송 경로에서 매번 동기 DB 조회로 수행해
      구독자가 늘수록 push 주기와 전달 지연이 함께 악화된다.
```

## 실제 실행 위치·명령·결과

| 위치 | 실행한 명령의 핵심(주소·인증값 비식별) | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 앱 VM | `curl http://127.0.0.1:8081/actuator/health` | 기준선·PR 버전 모두 `UP` | 두 JAR 모두 정상 기동 |
| 앱·부하 VM | `chronyc burst 4/4` → `chronyc makestep` → `chronyc sources -v` | 양쪽 동일 metadata NTP, 종료 표본 오차 각각 약 ±0.6 ms | 초기 2회 음수 지연은 폐기; 이후 지연 측정만 사용 |
| 부하 VM | `python3 wsbench_load.py --url ws://<앱 내부주소>:8080/ws/websocket --token-file <임시 JWT 파일> --clients <1, 5, 20 또는 50> --warmup-seconds 10 --duration-seconds 30 --output <실행>.json` | 50개에서 버전별 3회 결과는 위 표 참조. 단일 구독 스모크만 3초 예열·12초 관측 | 실제 WebSocket 업그레이드·STOMP CONNECT/SUBSCRIBE·MESSAGE 수신 경로. 토큰 값은 파일에서 읽었고 결과에 넣지 않음 |
| 앱 VM | `python3 wsbench_metrics.py --duration-seconds 45 --interval-seconds 2 --output <실행>.jsonl` | Hikari 대기 0, PR 버전 대여 증가, executor 큐 0 | CPU/풀/큐와 지연 원인을 분리 |
| 앱 VM | `jcmd <시험 앱 PID> Thread.print -l` | 브로커 스레드가 수신자 반복문 안의 primary 역할 조회에서 PG 응답 대기 | 코드 수준의 병목 위치 확인 |
| 앱 VM → DB 3노드 | `python3 wsbench_db_snapshot.py --output <전후>.json` | primary 1·standby 2, primary 거래 증가 | 역할을 확인하고 DB 측 변화 대조 |
| 앱 VM | `ss -nt state established` | 앱 연결 5개가 두 OpenProxy의 6432 포트로 향함 | 프록시 경유 검증 |
| 앱 VM | `python3 wsbench_cleanup.py` → `python3 wsbench_cleanup.py --delete` | 정확히 3개 시험 전용 계정 일치 후 삭제, 잔여 0 | 시험 데이터만 트랜잭션으로 정리 |

## 결론·한계·다음 결정

- **결론:** PR #373의 수신자별 최신 primary 역할 확인은 보안 경계로 의미가 있지만, 현재 방식 그대로는 50개 관리자 구독에서 실시간 대시보드 성능 비용이 크다. #373은 이 비용을 수용한 보안 수정으로 2026-10-01에 머지됐다. 이 후속 이슈는 성능 개선을 구현하지 않고 관측값과 한계를 공개한다. 당시 시험 자체는 #373 코드를 변경하거나 머지하지 않았다.
- **왜 0건 누락이어도 문제인가:** `sequence` 중간 누락은 없었다. 그러나 발행 호출이 동기로 느려져 애초에 계획한 300 ms 간격의 push가 생성되지 않았다. 따라서 “누락 0”은 목표 주기를 지켰다는 뜻이 아니다.
- **운영 코드와의 차이:** 실제 [DashboardPushScheduler](https://github.com/DocGrid/docgrid/blob/b425c3535194be6719b75ef79cf5100d9a86be97/backend/src/main/java/com/opensource/docgrid/domain/dashboard/event/DashboardPushScheduler.java#L38-L53)는 변경 이벤트가 있을 때만 집계를 수행하고 `fixedDelay`를 쓴다. 이번 시험은 합성 `fixedRate` publisher라 집계 SQL과 실제 이벤트 빈도는 재현하지 않는다. 실제 빈도가 낮으면 평시 영향은 작을 수 있지만, burst 때 동기 팬아웃 비용은 남는다.
- **부하 상한:** 50개 구독에서 이미 p95가 2초를 넘어 100·200개는 안전장치 기준으로 진행하지 않았다. 30초 반복 3회는 지속 부하·두 백엔드·장애 중 성능을 증명하지 않는다.
- **후속 후보:** 권한 변경 이벤트로 세션을 닫고 주기 재검증을 안전망으로 두거나, Redis epoch를 수신자별로 비교하는 방식을 검토할 수 있다. 다만 Pub/Sub만으로는 권한 회수 HTTP 200 시점의 즉시 차단을 보장하지 못하고, epoch 방식은 Redis 장애 시 fail-closed 정책이 필요하다. 어느 방식도 이번 시험에서 구현·검증한 결과로 제시하지 않는다.
- **정리:** 시험 앱을 중지하고, 정확히 일치하는 일회용 ADMIN 계정 3개와 그 `user_roles` 연결만 삭제했다. DB 재조회로 사용자 잔여 0건을 확인했다. 승인받은 임시 VM 2대와 `autoDelete=True` 부팅 디스크는 삭제 후 GCP 목록에서 잔여 0건을 확인했다. 로컬 임시 DB/JWT 설정·시험 JWT·SSH 개인 키도 삭제했다. 기존 OpenSQL 3노드는 모두 `RUNNING` 상태로 남겨 두었다.

```text
안전성·성능을 함께 보는 다음 설계 판단(아직 미구현)

권한 회수 요청 → DB 커밋 → 권한 변경 신호
                         │
                         ├─ 이벤트로 해당 세션 종료: 평상시 DB 조회 절감
                         │   └─ 비동기 전달 지연·유실 시 빈틈을 어떻게 막을지 필요
                         └─ epoch 동기 확인: 수신자별 Redis 조회
                             └─ Redis 장애라면 차단할지, primary 재조회할지 정책 필요

머지된 #373: 매 MESSAGE × 매 세션마다 primary DB 확인 → 새 발행 메시지 차단,
         하지만 실측 50개 구독 p95 약 2.4초.
다음 변경의 목표: 회수 이후 메시지 0건과 부하 지표를 모두 재검증.
```

```text
이번 증거 PR과 후속 성능 수정의 경계

2026-10-01 실행된 클라우드 시험
  ├─ 버전별·부하별 클라이언트 결과 12개 → runs/<버전>-n<구독자>-<회차>.json
  ├─ 앱 지표 시계열 10개         → metrics/<같은 실행>-metrics.jsonl
  ├─ 앱 journal 선별 기록        → 이 보고서의 한국어 중요 로그 표
  ├─ DB snapshot 원본            → 내부 IP 제거 후 전후 4개 파일 공개
  └─ JVM 덤프 전체 내용          → PID·스레드 ID·JVM 주소 제거, 끝 공백 정리 후 1116줄 공개
               │
               ▼
  이번 #375 PR: 측정 코드 사본 + 원본 수치 + 해석 + 누락 범위 공개
               │
               ▼
  별도 성능 Fix: 권한 회수 뒤 새 메시지 노출 0건을 유지하는 설계 구현
               │
               └─ 같은 조건으로 전후 재측정해야 개선됐다고 주장 가능

※ 이 도식의 마지막 성능 Fix와 재측정은 계획이며 실행 결과가 아니다.
```

## 실시간 앱 중요 로그 (한국어 분류)

| KST | 분류 | 한국어 해석 | 비식별 로그 요약 |
| --- | --- | --- | --- |
| 2026-10-01 21:20:42 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:20:42.618Z  WARN 2182 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/a` |
| 2026-10-01 21:20:42 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:20:42.624Z  WARN 2182 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'serverAnnotatedBeanRegistry' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration$ServerMcpAnnotatedBeans] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/applied to a currently created BeanPostProcessor [serverAnno` |
| 2026-10-01 21:20:42 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:20:42.653Z  WARN 2182 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'spring.ai.mcp.server.annotation-scanner-org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean ` |
| 2026-10-01 21:20:47 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:20:47.830Z  INFO 2182 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Starting...` |
| 2026-10-01 21:20:48 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:20:48.621Z  INFO 2182 --- [docgrid] [           main] com.zaxxer.hikari.pool.HikariPool        : docgrid-opensql-ha-pool - Added connection org.postgresql.jdbc.PgConnection@263f6e96` |
| 2026-10-01 21:20:48 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:20:48.674Z  INFO 2182 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Start completed.` |
| 2026-10-01 21:20:49 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `Database JDBC URL [Connecting through datasource 'HikariDataSource (docgrid-opensql-ha-pool)']` |
| 2026-10-01 21:21:05 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:21:05.310Z  WARN 2182 --- [docgrid] [           main] .s.s.UserDetailsServiceAutoConfiguration :` |
| 2026-10-01 21:21:08 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:21:08.497Z  INFO 2182 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/'` |
| 2026-10-01 21:21:08 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:21:08.807Z  INFO 2182 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8081 (http) with context path '/'` |
| 2026-10-01 21:21:08 | 앱 기동 | 백엔드가 초기화를 마치고 요청 수신 가능 | `2026-10-01T12:21:08.861Z  INFO 2182 --- [docgrid] [           main] c.opensource.docgrid.DocgridApplication  : Started DocgridApplication in 44.609 seconds (process running for 51.137)` |
| 2026-10-01 21:22:08 | WebSocket | 브로커의 연결·채널·큐 상태가 주기적으로 기록됨 | `2026-10-01T12:22:08.406Z  INFO 2182 --- [docgrid] [MessageBroker-2] o.s.w.s.c.WebSocketMessageBrokerStats    : WebSocketSession[0 current WS(0)-HttpStream(0)-HttpPoll(0), 0 total, 0 closed abnormally (0 connect failure, 0 send limit, 0 transport error)], stompSubProtocol[processed CONNECT(0)-CONNECTED(0)-DISCONNECT(0)], stompBrokerRelay[null], inboundChannel[pool size = 0, active threads = 0, queued tasks = 0, completed tasks = 0], outboundChannel[pool size = 0, active threads = 0, queued tasks ` |
| 2026-10-01 21:35:40 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopping DocGrid temporary WebSocket authorization benchmark...` |
| 2026-10-01 21:35:40 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:35:40.865Z  INFO 2182 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopping...` |
| 2026-10-01 21:35:40 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:35:40.866Z  INFO 2182 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopped.` |
| 2026-10-01 21:35:40 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:35:40.928Z  INFO 2182 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown initiated...` |
| 2026-10-01 21:35:40 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:35:40.931Z  INFO 2182 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown completed.` |
| 2026-10-01 21:35:41 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopped DocGrid temporary WebSocket authorization benchmark.` |
| 2026-10-01 21:36:01 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:36:01.330Z  WARN 3911 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/a` |
| 2026-10-01 21:36:01 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:36:01.332Z  WARN 3911 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'serverAnnotatedBeanRegistry' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration$ServerMcpAnnotatedBeans] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/applied to a currently created BeanPostProcessor [serverAnno` |
| 2026-10-01 21:36:01 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:36:01.356Z  WARN 3911 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'spring.ai.mcp.server.annotation-scanner-org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean ` |
| 2026-10-01 21:36:03 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:36:03.267Z  INFO 3911 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Starting...` |
| 2026-10-01 21:36:03 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:36:03.453Z  INFO 3911 --- [docgrid] [           main] com.zaxxer.hikari.pool.HikariPool        : docgrid-opensql-ha-pool - Added connection org.postgresql.jdbc.PgConnection@3d2ff73a` |
| 2026-10-01 21:36:03 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:36:03.456Z  INFO 3911 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Start completed.` |
| 2026-10-01 21:36:03 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `Database JDBC URL [Connecting through datasource 'HikariDataSource (docgrid-opensql-ha-pool)']` |
| 2026-10-01 21:36:13 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:36:13.794Z  WARN 3911 --- [docgrid] [           main] .s.s.UserDetailsServiceAutoConfiguration :` |
| 2026-10-01 21:36:15 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:36:15.763Z  INFO 3911 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/'` |
| 2026-10-01 21:36:16 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:36:16.002Z  INFO 3911 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8081 (http) with context path '/'` |
| 2026-10-01 21:36:16 | 앱 기동 | 백엔드가 초기화를 마치고 요청 수신 가능 | `2026-10-01T12:36:16.047Z  INFO 3911 --- [docgrid] [           main] c.opensource.docgrid.DocgridApplication  : Started DocgridApplication in 21.655 seconds (process running for 22.958)` |
| 2026-10-01 21:37:15 | WebSocket | 브로커의 연결·채널·큐 상태가 주기적으로 기록됨 | `2026-10-01T12:37:15.692Z  INFO 3911 --- [docgrid] [MessageBroker-1] o.s.w.s.c.WebSocketMessageBrokerStats    : WebSocketSession[0 current WS(0)-HttpStream(0)-HttpPoll(0), 1 total, 0 closed abnormally (0 connect failure, 0 send limit, 0 transport error)], stompSubProtocol[processed CONNECT(1)-CONNECTED(1)-DISCONNECT(0)], stompBrokerRelay[null], inboundChannel[pool size = 4, active threads = 0, queued tasks = 0, completed tasks = 9], outboundChannel[pool size = 4, active threads = 0, queued tasks ` |
| 2026-10-01 21:44:54 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopping DocGrid temporary WebSocket authorization benchmark...` |
| 2026-10-01 21:44:54 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:44:54.946Z  INFO 3911 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopping...` |
| 2026-10-01 21:44:54 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:44:54.947Z  INFO 3911 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopped.` |
| 2026-10-01 21:44:54 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:44:54.998Z  INFO 3911 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown initiated...` |
| 2026-10-01 21:44:55 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:44:55.003Z  INFO 3911 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown completed.` |
| 2026-10-01 21:44:55 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopped DocGrid temporary WebSocket authorization benchmark.` |
| 2026-10-01 21:45:03 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:45:03.411Z  WARN 4755 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/a` |
| 2026-10-01 21:45:03 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:45:03.413Z  WARN 4755 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'serverAnnotatedBeanRegistry' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration$ServerMcpAnnotatedBeans] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean getting eagerly injected/applied to a currently created BeanPostProcessor [serverAnno` |
| 2026-10-01 21:45:03 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:45:03.435Z  WARN 4755 --- [docgrid] [           main] trationDelegate$BeanPostProcessorChecker : Bean 'spring.ai.mcp.server.annotation-scanner-org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties' of type [org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerProperties] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying). Is this bean ` |
| 2026-10-01 21:45:05 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:45:05.545Z  INFO 4755 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Starting...` |
| 2026-10-01 21:45:05 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:45:05.734Z  INFO 4755 --- [docgrid] [           main] com.zaxxer.hikari.pool.HikariPool        : docgrid-opensql-ha-pool - Added connection org.postgresql.jdbc.PgConnection@dd3e1e3` |
| 2026-10-01 21:45:05 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:45:05.737Z  INFO 4755 --- [docgrid] [           main] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Start completed.` |
| 2026-10-01 21:45:05 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `Database JDBC URL [Connecting through datasource 'HikariDataSource (docgrid-opensql-ha-pool)']` |
| 2026-10-01 21:45:17 | 경고 | 앱 경고 발생; 아래 비식별 원문으로 영향 범위 판단 | `2026-10-01T12:45:17.090Z  WARN 4755 --- [docgrid] [           main] .s.s.UserDetailsServiceAutoConfiguration :` |
| 2026-10-01 21:45:19 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:45:19.767Z  INFO 4755 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/'` |
| 2026-10-01 21:45:20 | HTTP 기동 | 앱 또는 관측 포트가 열림 | `2026-10-01T12:45:20.187Z  INFO 4755 --- [docgrid] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8081 (http) with context path '/'` |
| 2026-10-01 21:45:20 | 앱 기동 | 백엔드가 초기화를 마치고 요청 수신 가능 | `2026-10-01T12:45:20.231Z  INFO 4755 --- [docgrid] [           main] c.opensource.docgrid.DocgridApplication  : Started DocgridApplication in 23.426 seconds (process running for 24.768)` |
| 2026-10-01 21:46:19 | WebSocket | 브로커의 연결·채널·큐 상태가 주기적으로 기록됨 | `2026-10-01T12:46:19.662Z  INFO 4755 --- [docgrid] [MessageBroker-1] o.s.w.s.c.WebSocketMessageBrokerStats    : WebSocketSession[50 current WS(50)-HttpStream(0)-HttpPoll(0), 50 total, 0 closed abnormally (0 connect failure, 0 send limit, 0 transport error)], stompSubProtocol[processed CONNECT(50)-CONNECTED(50)-DISCONNECT(0)], stompBrokerRelay[null], inboundChannel[pool size = 4, active threads = 0, queued tasks = 0, completed tasks = 300], outboundChannel[pool size = 4, active threads = 0, queued` |
| 2026-10-01 21:51:59 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopping DocGrid temporary WebSocket authorization benchmark...` |
| 2026-10-01 21:51:59 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:51:59.834Z  INFO 4755 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopping...` |
| 2026-10-01 21:51:59 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `2026-10-01T12:51:59.835Z  INFO 4755 --- [docgrid] [ionShutdownHook] o.s.m.s.b.SimpleBrokerMessageHandler     : Stopped.` |
| 2026-10-01 21:51:59 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:51:59.913Z  INFO 4755 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown initiated...` |
| 2026-10-01 21:51:59 | DB 풀 | OpenSQL 연결 풀 시작 또는 연결 상태 변경 | `2026-10-01T12:51:59.926Z  INFO 4755 --- [docgrid] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : docgrid-opensql-ha-pool - Shutdown completed.` |
| 2026-10-01 21:52:00 | 앱 종료 | 앱 또는 하위 서비스가 종료됨 | `Stopped DocGrid temporary WebSocket authorization benchmark.` |
