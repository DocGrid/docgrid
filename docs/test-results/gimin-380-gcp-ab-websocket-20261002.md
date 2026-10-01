# GCP 2백엔드·공용 Redis·내부 LB WebSocket 검증

- 관련 이슈: [#380](https://github.com/DocGrid/docgrid/issues/380)
- 실행일: 2026-10-02 KST
- 앱 기준 커밋: `18a313f990f300d925eb92a56b57d3ccb1c4bfc0` (A/B 동일). 이 브랜치의 시험 fixture·계측 스크립트는 별도로 배치했다.
- 판정: **접속 분산·교차 백엔드 권한 회수·계획된 A 종료 후 새 연결은 통과. A에서만 발생한 대시보드 push의 B 구독자 전달은 실패.**
- 주의: 이것은 시험 VM에서 실행한 Spring Boot/JUnit fixture 결과다. 상시 운영 배포나 Docker 컨테이너 가동 결과가 아니다.

## 1. 시험 전에 정의한 질문과 완료 기준

| 질문 | 판정 방법 |
| --- | --- |
| 내부 LB가 물리 WebSocket 연결을 A/B에 나누는가? | 1·5·20·50개 k6 연결과 같은 시간대 각 JVM의 인증 완료 세션 수를 대조한다. 정확한 50:50은 요구하지 않는다. |
| 공용 Redis와 실제 DB 회수 이후 두 백엔드가 같은 권한을 보는가? | 각 백엔드에 25개씩 구독한 뒤 실제 관리자 API로 `ADMIN`을 회수한다. 회수 전 양쪽 수신, HTTP 200, Redis epoch·캐시, 이후 발행·수신·종료, 새 SUBSCRIBE를 각각 확인한다. |
| A 정상 종료 때 기존 연결과 새 연결은 어떻게 다른가? | A에 물리 연결을 고정해 메시지를 확인한 후 A fixture만 종료한다. 기존 소켓 종료와 내부 LB를 통한 새 메시지 수신 사이를 따로 잰다. |
| A에서만 만든 push가 B 구독자에게도 가는가? | A 발행/B 미발행 상태에서 A/B 각 25개 구독을 두 번 실행한다. 실제 연결 수와 발행 수를 함께 확인한다. |

```text
내부 시험 경로

전용 부하 VM ── HTTP/WebSocket ──▶ 내부 HTTP LB ──┬──▶ 백엔드 A
                                               └──▶ 백엔드 B
                                                       │
백엔드 A/B ── 역할 캐시·토큰 폐기 조회 ───────────────▶ 공용 Redis 1대
백엔드 A/B ── OpenProxy 2대 ───────────────────────▶ 기존 OpenSQL 3노드

대시보드 브로커는 위 그림의 공용 Redis가 아니다.
A와 B 각각의 Spring SimpleBroker가 자기 JVM의 구독만 관리한다.
```

이번에 만든 VM은 A의 기존 시험 VM과 별개인 B `e2-standard-2`, Redis `e2-small`, 전용 부하 `e2-standard-2`다. A도 `e2-standard-2`다. 네 VM 모두 실행 시 `Rocky Linux 9.8 (Blue Onyx) x86_64`로 확인했다. 사용자의 기존 OpenSQL DB 3노드 OS와 이 **백엔드·Redis·부하 VM의 OS 버전을 혼동하지 않는다**. 외부 주소나 프로젝트 식별자는 원본 기록 전에 제외했다.

## 2. 부하 실행: 연결 수와 도착 백엔드

k6는 **GCP 내부 전용 부하 VM**에서 실행했다. 각 회차는 예열 10초·측정 30초, 300ms 간격의 시험용 대시보드 발행을 사용했다. A/B의 `publisher-events.tsv`는 `StompSessionRegistry.authenticatedSessionCount()`의 로컬 값을 매 발행 시각에 기록한다. 인접한 실행은 경계 초를 중복 집계하지 않도록 `[시작, 종료)` 구간으로 계산했다.

| run ID | 요청/연결 | 수신 메시지 | A/B 최대 인증 세션 | k6 오류 | 메시지 지연 p95 / p99 | 근거 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `ab380-lb-n1-r1` | 1 / 1 | 100 | 0 / 1 | 0 | 18 / 19.01ms | [요약](evidence/issue-380/runs/ab380-lb-n1-r1/k6/summary.json), [샘플](evidence/issue-380/runs/ab380-lb-n1-r1/k6/samples.jsonl) |
| `ab380-lb-n5-r1` | 5 / 5 | 500 | 2 / 3 | 0 | 17 / 18ms | [요약](evidence/issue-380/runs/ab380-lb-n5-r1/k6/summary.json), [샘플](evidence/issue-380/runs/ab380-lb-n5-r1/k6/samples.jsonl) |
| `ab380-lb-n20-r1` | 20 / 20 | 2,000 | 10 / 10 | 0 | 16 / 52.01ms | [요약](evidence/issue-380/runs/ab380-lb-n20-r1/k6/summary.json), [샘플](evidence/issue-380/runs/ab380-lb-n20-r1/k6/samples.jsonl) |
| `ab380-lb-n50-r2` | 50 / 50 | 5,000 | 25 / 25 | 0 | 19 / 20.01ms | [요약](evidence/issue-380/runs/ab380-lb-n50-r2/k6/summary.json), [샘플](evidence/issue-380/runs/ab380-lb-n50-r2/k6/samples.jsonl) |

각 실행의 KST 시각·구독 수·종료 코드는 해당 [run 디렉터리](evidence/issue-380/runs/)의 `status.txt`와 `k6/manifest.json`에 분리해 뒀다. A/B 도착의 독립 근거는 [A 발행·세션 로그](evidence/issue-380/fixtures/A/publisher-events.tsv), [B 발행·세션 로그](evidence/issue-380/fixtures/B/publisher-events.tsv)다. `p95/p99`는 테스트 payload에 담은 **발행 시각 → k6 클라이언트 수신 시각** 차이다. 측정 후 읽기 전용 `chronyc tracking`에서 A/B/부하 VM의 leap status는 모두 `Normal`, system-time 편차는 각각 약 +5.6µs/+0.05µs/−16.6µs였다. 이 확인은 실행 중 매 프레임의 시계 오차를 증명하지는 않는다.

첫 50명 회차 `ab380-lb-n50-r1`은 k6 자체는 50연결·5,001수신·오류 0건이었지만, A/B 세션을 가져오던 `/actuator/prometheus` 탐침이 HTTP 401로 **47회 모두 실패**했다. 따라서 그 회차로 접속 분산을 주장하지 않았다. [실패한 탐침 요약](evidence/issue-380/runs/ab380-lb-n50-r1/sessions/summary.json)을 남기고, A/B fixture의 인증 세션 수를 직접 수집하도록 바꿔 `r2`를 다시 실행했다. HTTP 401의 내부 보안 설정 원인은 이 시험에서 확정하지 않았다.

준비 단계에서도 실패·수정이 있었다. 처음 시도한 프록시 전용 서브넷 주소 범위는 기본 VPC의 예약 범위와 충돌해 **생성되지 않았고**, 다른 비충돌 범위로 다시 만들었다. 첫 A/B fixture 실행은 테스트 설정의 `management.server.port=0` 때문에 건강 검사 포트가 무작위로 잡혀 LB에서 unhealthy였다. `MANAGEMENT_SERVER_PORT=8081`을 명시한 다음 실행에서 A/B readiness와 LB healthy를 확인한 뒤 최종 계측을 진행했다. 또한 VM 이름에 `load`가 포함된 앱 VM을 처음에는 부하 VM으로 잘못 선택했다. 전용 부하 VM을 앱/백엔드 이름 제외 조건으로 다시 식별했고, **이 문서의 k6 실행은 모두 바로잡은 전용 부하 VM에서만 실행**했다. 앞선 잘못된 선택·건강 검사 실패는 성능 근거로 쓰지 않았다.

## 3. 실제 역할 회수: 두 백엔드의 기존 구독과 신규 구독

`ab380-revoke-a25-b25-r1`에서는 A/B에 각각 물리 구독 25개를 직접 연결했다. 관리자 API는 실제 OpenSQL primary에 쓰고, 앱의 역할 변경 경로가 공용 Redis를 무효화한다.

| 단계 | 관측값 | 해석 |
| --- | ---: | --- |
| 회수 전 준비된 구독 | A 25, B 25 | 한쪽이 연결되지 않아 수신 0인 공허한 시험을 방지했다. |
| 회수 전 실제 수신 | A 274, B 275 | 두 서버에서 발행·전달이 동작했다. |
| 실제 역할 회수 API | HTTP 200 | DB 역할 회수 경로가 성공 응답했다. |
| 공용 Redis | epoch `1`, 역할 캐시 키 존재 `0` | 회수 경로가 cache generation 증가와 캐시 삭제를 반영했다. |
| HTTP 200 이후 A/B 발행 | 각각 23회 성공 | 관측 기간에도 발행원이 멈추지 않았다. |
| HTTP 200 이후 새 판정 후보 메시지 | 0건 | 이 한 회차에서 회수 후 새 발행이 기존 구독자에게 노출되지 않았다. |
| 기존 WebSocket 종료 | 50개 | 회수 이후 연결 종료/오류 이벤트를 50개 수집했다. |
| 새 대시보드 `SUBSCRIBE` | A/B 모두 STOMP `ERROR` | 회수된 JWT의 관리자 토픽 신규 구독도 거부됐다. |

[수신·회수 원본 이벤트](evidence/issue-380/runs/ab380-revoke-a25-b25-r1/events.jsonl), [신규 SUBSCRIBE 재검증](evidence/issue-380/runs/ab380-revoked-subscribe-r2.json)을 보존했다. **한 번의 관측으로 모든 경합에서 항상 0건이라고 증명하지는 않는다.** HTTP 200 이전에 이미 허용된 메시지가 늦게 도착할 가능성은 별도 범주로 분리했다.

처음 만든 `ab380-revoked-reconnect-r1` 탐침은 STOMP `CONNECT`만 보고 A/B 모두 `허용`이라고 출력했다. 이것은 앱 결함 판정이 아니라 **시험 판정 기준 오류**다. 이 앱은 유효한 일반 사용자의 CONNECT를 허용하고, 관리자 토픽의 실제 인가는 `SUBSCRIBE` 단계에서 검사한다. 수정 탐침으로 다시 실행한 `r2`는 두 서버 모두 `거부_ERROR`였다. 잘못된 [첫 탐침 출력](evidence/issue-380/runs/ab380-revoked-reconnect-r1.json)도 삭제하지 않았다.

## 4. A 정상 종료 중 기존 소켓과 새 LB 연결

`ab380-a-stop-reconnect-r1`은 **B 시험 계정의 유효한 JWT**를 써서 A에 기존 구독을 만들었다. A fixture를 정상 종료하면 A 자신의 시험 계정은 정리되지만, B 계정은 계속 남아 있으므로 재접속 실패 원인과 혼동되지 않는다.

| KST / 구간 | 관측 |
| --- | --- |
| 05:42:58.394 | A 직접 구독에서 대시보드 메시지를 실제로 수신. 그 뒤 종료 주입을 허용. |
| 05:43:24(초 단위) | A fixture 정상 종료 요청. VM을 끄거나 프로세스를 `kill -9` 하지 않았다. |
| 05:43:34.667 | A의 기존 WebSocket이 종료됨. |
| 05:43:35.010 | 내부 LB를 통한 새 연결의 메시지를 첫 시도에 수신. 기존 연결 종료→새 수신 **342.4ms**. |
| 종료 후 LB 상태 | A `UNHEALTHY`, B `HEALTHY`를 확인. |

[시간축 원본 이벤트](evidence/issue-380/runs/ab380-a-stop-reconnect-r1/events.jsonl), [A 종료·계정 정리](evidence/issue-380/fixtures/A/launch-status.txt), [B 지속 실행 로그](evidence/issue-380/fixtures/B/publisher-events.tsv). **342.4ms는 장애 주입→복구 RTO가 아니라 기존 연결이 닫힌 시점→새 메시지 수신 시간**이다. 종료 요청→새 수신은 기록된 초 단위 요청시각 기준 대략 11초이며, 진행 중이던 WebSocket이 B로 무중단 이전된 것도 아니다.

A가 내려간 상태의 `ab380-bonly-n50-r1`에서도 k6 50/50 연결, 5,000수신, 오류 0건, p95 22ms·p99 25ms를 확인했고, B 로컬 인증 세션 최대값은 50이었다. [B 단독 요약](evidence/issue-380/runs/ab380-bonly-n50-r1/k6/summary.json), [원본 샘플](evidence/issue-380/runs/ab380-bonly-n50-r1/k6/samples.jsonl). 이것은 **새 연결의 복구**만 확인하며, 갑작스러운 프로세스 강제 종료·패킷 DROP의 복구 시간은 측정하지 않았다.

## 5. 발견한 미완성 경계: A 단독 push는 B에 전달되지 않음

두 백엔드가 모두 300ms마다 발행하면 각자의 구독자가 계속 메시지를 받는다. 이 상태만 보면 교차 백엔드 알림도 되는 듯 보일 수 있다. 이를 분리하려고 A만 `sendDashboardUpdate()`를 호출하고 B는 프로세스·구독을 유지하되 발행을 끈 상태로 반복했다.

| run ID | 인증된 구독 | A 수신 | B 수신 | 발행·세션 확인 |
| --- | ---: | ---: | ---: | --- |
| `ab380-one-sided-fanout-r1` | A 25 / B 25 | 850 | **0** | [수신 이벤트](evidence/issue-380/runs/ab380-one-sided-fanout-r1/events.jsonl) |
| `ab380-one-sided-fanout-r2` | A 25 / B 25 | 853 | **0** | 같은 구간 A `성공` 37행, B `미발행` 36행, 양쪽 인증 세션 최대 25. [수신 이벤트](evidence/issue-380/runs/ab380-one-sided-fanout-r2/events.jsonl), [A 로그](evidence/issue-380/fixtures/A-04/publisher-events.tsv), [B 로그](evidence/issue-380/fixtures/B-04/publisher-events.tsv) |

코드의 `WebSocketConfig`는 JVM 내부 `enableSimpleBroker("/topic", "/queue")`를 사용하고, `DashboardWebSocketController`는 자기 JVM의 `SimpMessagingTemplate.convertAndSend()`를 호출한다. 따라서 위 결과는 **공용 Redis가 권한·캐시를 공유해도 대시보드 메시지 브로커까지 공유하지는 않는다**는 해석과 일치한다. 이 상태를 “대시보드 push의 완성된 2백엔드 HA”로 표현하면 안 된다. 다른 노드에서만 발생한 이벤트가 B의 구독자에게 필요한지 제품 요구사항을 정하고, 필요하다면 별도의 노드 간 갱신 신호 또는 공유 브로커와 재접속 시 snapshot 재동기화를 설계해야 한다. 이 PR은 관측 코드·근거만 다루며 운영 코드 수정은 포함하지 않는다.

## 6. 재현 명령, 판정, 정리

아래 명령은 실행한 형태를 공개 가능한 별칭으로 적은 것이다. 실제 내부 주소·프로젝트·계정·암호·JWT는 기록 전에 제거했다. 모든 클라우드 부하는 SSH 터널을 경유한 로컬 부하가 아니라 **GCP 내부 전용 부하 VM**에서 실행했다.

| 실행 위치 | 절차/명령 형식 | 주요 결과 | 해석·근거 |
| --- | --- | --- | --- |
| A/B VM | `launch_ab_dashboard_fixture.sh <run-id> <output-dir> <0600-secret-dir> <true/false>` | A/B 같은 앱 기준 커밋. 일방향 발행 run04에 배치한 fixture의 양쪽 SHA-256은 `c17d92a2…`로 일치. 종료 코드 0, 실행마다 시험 사용자 2개 정리. 앞선 run03 fixture의 별도 파일 해시는 보존하지 못했다. | [run03 A](evidence/issue-380/fixtures/A/launch-status.txt), [run03 B](evidence/issue-380/fixtures/B/launch-status.txt), [run04 A](evidence/issue-380/fixtures/A-04/launch-status.txt), [run04 B](evidence/issue-380/fixtures/B-04/launch-status.txt) |
| 부하 VM | `run_dashboard_ab_load.sh <run-id> <1\|5\|20\|50> <new-output-dir> <0600-token-file>` | 네 규모 모두 연결 수 일치·오류 0. B 단독 50개도 통과 | 각 실행의 `status.txt`, `k6/manifest.json`, `k6/summary.json`, `k6/samples.jsonl` |
| 부하 VM | `websocket_dashboard_revocation.py` A/B 직접 각 25개 → 실제 회수 API → 8초 관측 | 회수 200, 양쪽 기존 세션 50개 종료, 이후 새 후보 수신 0 | [원본 이벤트](evidence/issue-380/runs/ab380-revoke-a25-b25-r1/events.jsonl) |
| 부하 VM | `probe_ab_websocket_auth.py`로 두 서버의 새 `SUBSCRIBE` | A/B 모두 `거부_ERROR` | [재시험](evidence/issue-380/runs/ab380-revoked-subscribe-r2.json) |
| 부하 VM·A VM | `websocket_ab_failover.py` 준비 확인 → A fixture `stop` → LB 새 구독 | 기존 소켓 종료 후 342.4ms, 첫 시도 복구 | [시간축](evidence/issue-380/runs/ab380-a-stop-reconnect-r1/events.jsonl) |
| 부하 VM·A/B VM | `websocket_dashboard_revocation.py --observe-only`와 B 발행 `false` | 2회 모두 A 수신 양수, B 수신 0 | [r1](evidence/issue-380/runs/ab380-one-sided-fanout-r1/events.jsonl), [r2](evidence/issue-380/runs/ab380-one-sided-fanout-r2/events.jsonl) |
| 로컬 | `python3 -B -m unittest discover -s scripts/opensql -p 'test_websocket_dashboard_revocation.py' -v`; `bash -n …`; `./backend/gradlew -p backend compileTestJava --offline --no-daemon` | Python 4/4, Bash 구문 통과, Java test compile 성공 | 클라우드 E2E와 별도의 로컬 검증이며 전체 Gradle 테스트는 실행하지 않았다. |

관련 인프라·정리의 숫자와 남겨 둔 상태는 [운영 체크 기록](evidence/issue-380/infra-checks-20261002.md)에 분리했다. 모든 `samples.jsonl`은 k6 원시 스트림을 파일로 쓰기 **전에** 허용 지표명·수치·KST만 선택했다. HTTP 태그·URL·JWT는 수집 파일에 쓰지 않았다. 공개 전 전체 증거 폴더를 다시 스캔해 내부 IP, 프로젝트명, 토큰, 시험 사용자 이메일 패턴 매치가 0개임을 확인했다.

## 7. 결과가 뜻하지 않는 것과 남은 결정

- Redis는 **한 VM**이다. 공용으로 쓰는 것과 Redis 자체가 HA인 것은 다르다.
- 두 A/B 앱 프로세스는 시험 전용 JUnit fixture였다. 시험 종료 후 테스트 사용자·비밀 복사본을 지우기 위해 정상 종료했고, 현재 VM은 `RUNNING`이나 LB의 A/B 백엔드는 모두 `UNHEALTHY`다. 다음 시험 때 앱을 다시 배치·기동해야 한다. 유료 VM·디스크·LB는 사용자가 유지 요청한 상태 그대로 남아 비용이 계속 발생한다.
- 실제 PDF 업로드·Worker·MinIO/S3·RAG 개인 알림, 강제 프로세스 종료·네트워크 DROP, Redis 장애·OpenProxy 장애·OpenSQL primary 장애는 이 실행으로 검증하지 않았다.
- 지연 p95/p99는 이 작은 시험 부하의 관측치다. CPU·메모리·장시간 안정성을 함께 계측하지 않아 최대 처리량이나 용량 계획으로 해석할 수 없다.
- 후속 구현 판단은 **노드 간 대시보드 갱신 전파**가 우선이다. 그 뒤 같은 일방향 발행 시험의 B 수신을 0이 아닌 값으로 재검증해야 2백엔드 대시보드 push 완료를 주장할 수 있다.
