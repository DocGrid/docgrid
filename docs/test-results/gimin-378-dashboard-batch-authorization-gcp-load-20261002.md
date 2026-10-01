# 대시보드 일괄 인가 GCP 부하·권한 회수 검증 — 2026-10-02

관련 이슈: [#378](https://github.com/DocGrid/docgrid/issues/378) · 결과 PR: [#379](https://github.com/DocGrid/docgrid/pull/379) · 성능 수정: [#377](https://github.com/DocGrid/docgrid/pull/377)

> 이 문서는 **2026-10-02 실제 GCP에서 실행한 결과**다. 앱·부하 VM과 기존 OpenSQL 3노드는 시험 중 실행 상태였으며, 앱은 두 OpenProxy 주소를 사용했다. 공개 증거에는 프로젝트 ID, 내부·외부 IP, 호스트명, 계정 ID, JWT, DB 암호를 기록하지 않았다. 아래의 모든 시각은 KST다. 부하 시험 후 **VM 2대와 Redis는 유지**하고, 시험용 Java 프로세스·계정·토큰·전송용 DB 암호 복사본만 정리했다.

## 결론

| 비교 조건 | 수정 전: 수신 프레임 / p95 | 수정 후: 수신 프레임 / p95 | 판단 |
| --- | ---: | ---: | --- |
| 구독 1명, 3초 예열 + 12초 관측 | 40건 / 15.05 ms | 40건 / 23.15 ms | 단일 구독은 개선을 주장하지 않는다. 수정 전 첫 두 스모크는 로그 수집 실패로 비교에서 제외했다. |
| 구독 5명, 10초 예열 + 30초 관측 | 500건 / 207 ms | 500건 / 19.05 ms | 발행량은 같고 지연이 감소했다. |
| 구독 20명, 동일 조건 | 625건 / 942.4 ms | 2,000건 / 19 ms | 수신량 3.2배, p95 약 49.6배 감소. |
| 구독 50명, 각 3회 중앙값 | 634건 / 2,269.35 ms | 5,000건 / 19 ms | 수신량 약 7.9배, p95 약 119.4배 개선. |

수정 전 50명 3회는 **634·640·634건 / p95 2,269.35·2,250·2,273.05 ms**, 수정 후 3회는 **5,000·5,000·5,000건 / p95 24·19·16 ms**였다. 모든 본 측정에서 요청한 WebSocket 구독 수가 연결됐고, k6 오류·음수 시계 차이·중간 순번 누락·중복은 각각 0건이었다. **한 환경의 단기 관측치**이지 최대 용량·장기 안정성 보장은 아니다.

```text
그림 1 — 실제 측정 경로와 구분한 부하

GCP 내부 k6 VM (Rocky Linux 9.8, x86_64, 2 vCPU)
  └─ k6 STOMP 클라이언트 1 / 5 / 20 / 50개
        │ WebSocket /ws/websocket, 시험용 ADMIN JWT
        ▼
GCP 내부 앱 VM (Rocky Linux 9.8, x86_64, 2 vCPU)
  ├─ Spring Boot + Hikari + SimpleBroker
  ├─ 300 ms마다 합성 DashboardSummary 발행
  └─ 로컬 Redis 서비스 (권한·세션 경로)
        │ 앱 JDBC: OpenProxy A와 B의 내부 포트
        ▼
OpenProxy 2대 → OpenSQL primary/standby 3노드
                 DB VM: Rocky Linux 9.7, x86_64

같은 앱·부하 VM에서 코드 커밋만 수정 전 → 수정 후로 교체했다.
PDF 인덱싱·임베딩·실제 대시보드 집계 SQL은 이 분리 측정에 넣지 않았다.
```

이 도식의 앱 경로는 단순 TCP 포트 검사만이 아니다. 실제 Spring fixture가 OpenProxy JDBC URL로 기동해 시험 계정을 만들고, k6가 JWT로 WebSocket에 연결해 발행 메시지를 수신했다. Flyway는 **시험 중 비활성화**해 마이그레이션 작업이 부하 결과에 섞이지 않게 했다. 일회용 사용자 2명을 버전별로 따로 만들고 종료 시 삭제했다.

## 무엇을 비교했나

| 구분 | 값 |
| --- | --- |
| 수정 전 코드 | `89038519b35ed82cc32c496502f01efa8a9a84f6`: 수신자마다 동기 primary 역할 조회 |
| 수정 후 코드 | `0e249c0e89f9aed1a148cb80e4d55bc7d11f186c`: push마다 일괄 primary 판정 후 수신자별 메모리 확인 |
| 공통 fixture | `DashboardCloudLoadTest`와 같은 `build.gradle` 시험 task를 두 소스 트리에 동일하게 적용. fixture 파일 SHA-256 일치 확인 |
| 부하 도구 | 공식 k6 **2.3.0**, GCP 내부 부하 VM에서 실행 |
| 런타임 | Java 17.0.20.1, Rocky Linux 9.8 `x86_64` 앱·부하 VM 각 `e2-standard-2` |
| 합성 발행 | `DashboardWebSocketController.sendDashboardUpdate`에 300 ms 간격으로 순번과 발행 시각을 넣음 |
| 측정 | 실제 STOMP CONNECT·SUBSCRIBE·MESSAGE 수신. 발행 시각→클라이언트 수신 지연 p50·p95·p99, 연결·수신·오류·음수 시계·순번 누락·중복 |
| 시간 동기화 | 두 VM 모두 `chronyc burst 4/4` → `makestep`; 양쪽 `Leap status: Normal` |
| 시험용 비밀 | 앱·DB 암호는 승인된 암호화 SSH/SCP 경로로 임시 전달. 비밀값은 출력·원시 로그·Git에 남기지 않고 종료 후 복사본 제거 |

```text
그림 2 — 동기 DB 조회가 팬아웃을 늦춘 수정 전 경로

fixture: 300 ms마다 발행 시도
  → DashboardWebSocketController.sendDashboardUpdate(summary)
  → Spring SimpleBroker가 구독자 50명에게 MESSAGE 생성
      ├─ 세션 1: outbound interceptor → primary 확인 → 사용자 역할 SELECT
      ├─ 세션 2: outbound interceptor → primary 확인 → 사용자 역할 SELECT
      ├─ …
      └─ 세션 50: outbound interceptor → primary 확인 → 사용자 역할 SELECT
  → convertAndSend 반환 후에야 다음 발행 시도

실측: 50명 p95 2.25~2.27초, 30초에 634~640건만 수신.
20명 회차의 pg_stat_statements 증가: primary 확인 892회,
사용자별 역할 조회 892회. 누적 호출에는 연결·백그라운드 활동도 섞일 수 있다.
```

```text
그림 3 — 수정 후 일괄 판정 경로

fixture: summary 생성 → sendDashboardUpdate(summary)
  → 현재 ADMIN 후보 사용자 집합을 primary에서 일괄 조회
  → 비노출 내부 헤더에 이번 push의 판정 snapshot을 보관
  → SimpleBroker가 50개 MESSAGE를 생성
      ├─ 세션 1: snapshot에 ADMIN이면 전송
      ├─ 세션 2: snapshot에 ADMIN이면 전송
      ├─ …
      └─ 세션 50: 같은 snapshot을 사용, 수신자별 DB 조회 없음

실측: 50명 매회 5,000건 수신, p95 24·19·16 ms.
20명 회차의 pg_stat_statements 증가:
일괄 조회 141회 + 사용자별 조회 20회 + primary 확인 161회.
사용자별 20회를 발행별 비용이라고 단정하지 않는다.
```

## 회차별 원본 측정값

각 행은 별도 run ID·`manifest.json`·`summary.json`·`samples.jsonl`을 가진다. `samples.jsonl`은 k6 원시 JSON을 메모리에서 **허용한 지표·시각·숫자만** 추려 작성했다. JWT·URL·태그는 저장 전에 버렸다. [회차별 증거](evidence/issue-378/gcp-runs/)와 [앱 발행·권한 회수 로그](evidence/issue-378/gcp-app/)를 참조한다.

| 버전 / run ID | 구독 | 관측 | 수신 | p50 / p95 / p99 (ms) | 오류 / 음수 / 순번 누락·중복 | 비식별 표본 | 결과 |
| --- | ---: | ---: | ---: | --- | --- | ---: | --- |
| 전 `b378-before-n1-smoke-01` | 1 | 12초 | 40 | 14 / 54.15 / 60.66 | 0 / 0 / 0·0 | **0** | 로그 수집 실패, 비교 제외 |
| 전 `b378-before-n1-smoke-02` | 1 | 12초 | 40 | 14 / 16 / 16 | 0 / 0 / 0·0 | **0** | 로그 수집 실패, 비교 제외 |
| 전 `b378-before-n1-smoke-03` | 1 | 12초 | 40 | 12 / 15.05 / 16 | 0 / 0 / 0·0 | 81 | 통과 |
| 전 `b378-before-n5-01` | 5 | 30초 | 500 | 107 / 207 / 209.01 | 0 / 0 / 0·0 | 1,005 | 통과 |
| 전 `b378-before-n20-01` | 20 | 30초 | 625 | 514 / 942.4 / 964.76 | 0 / 0 / 0·0 | 1,269 | 통과 |
| 전 `b378-before-n50-01` | 50 | 30초 | 634 | 1,229 / 2,269.35 / 2,362 | 0 / 0 / 0·0 | 1,318 | 통과 |
| 전 `b378-before-n50-02` | 50 | 30초 | 640 | 1,172 / 2,250 / 2,343.22 | 0 / 0 / 0·0 | 1,330 | 통과 |
| 전 `b378-before-n50-03` | 50 | 30초 | 634 | 1,182.5 / 2,273.05 / 2,365.34 | 0 / 0 / 0·0 | 1,318 | 통과 |
| 후 `b378-after-n1-smoke-01` | 1 | 12초 | 40 | 19 / 23.15 / 26.61 | 0 / 0 / 0·0 | 81 | 통과 |
| 후 `b378-after-n5-01` | 5 | 30초 | 500 | 15 / 19.05 / 53.01 | 0 / 0 / 0·0 | 1,005 | 통과 |
| 후 `b378-after-n20-01` | 20 | 30초 | 2,000 | 15 / 19 / 58.01 | 0 / 0 / 0·0 | 4,020 | 통과 |
| 후 `b378-after-n50-01` | 50 | 30초 | 5,000 | 15 / 24 / 57 | 0 / 0 / 0·0 | 10,049 | 통과 |
| 후 `b378-after-n50-02` | 50 | 30초 | 5,000 | 14 / 19 / 25 | 0 / 0 / 0·0 | 10,049 | 통과 |
| 후 `b378-after-n50-03` | 50 | 30초 | 5,000 | 13 / 16 / 20 | 0 / 0 / 0·0 | 10,050 | 통과 |

실패한 첫 두 스모크는 k6의 `summary.json` 자체는 정상(수신 각 40건)이었으나 실시간 표본이 0건이었다. 원인은 **Rocky Linux 9의 Python 3.9가 k6의 나노초 ISO 시각을 `datetime.fromisoformat`으로 파싱하지 못해** 허용 대상 표본 81건을 버린 것이다. 거부 사유를 태그·주소 없이 분류해 확인하고, 마이크로초 이하를 버리는 파서 수정 및 로컬 회귀 테스트 4건 통과 후 `smoke-03`을 새 run ID로 실행했다. 실패 파일도 삭제·덮어쓰기하지 않았다.

유효 회차 중에도 `before-n20-01`, `after-n50-01`, `after-n50-02`의 실시간 `dashboard_received` 표본은 각각 **요약값보다 1건 적다**(624/625, 4,999/5,000, 4,999/5,000). 각 회차의 `dashboard_latency_ms` 표본은 요약의 수신 건수와 일치하고, k6 summary는 정상 완료했다. 필터는 해당 1행을 `invalid_allowed_point`로 분류했지만 원시 행을 보존하지 않아 정확한 형식 원인을 사후 확인할 수 없다. 따라서 **수신량·p95 판정은 k6 summary 기준**이며, 이 세 회차의 개별 `dashboard_received` 이벤트 타임라인은 1건씩 불완전하다. 총 표본 유실을 0이라고 주장하지 않는다.

## DB 비용과 실제 권한 회수

| 20명 부하 전후 primary 누적 증가 | 수정 전 | 수정 후 | 해석 |
| --- | ---: | ---: | --- |
| 사용자별 역할 조회 | 892회 | 20회 | 수신자별 동기 조회가 사라졌다는 근거. 수정 후 남은 20회는 연결·기타 경로가 섞일 수 있다. |
| ADMIN 일괄 조회 | 해당 SQL 없음 | 141회 | push 단위의 일괄 판정으로 전환됐다. |
| `pg_is_in_recovery()` primary 확인 | 892회 | 161회 | 역할 조회 패턴과 함께 감소. |
| 위 SQL의 누적 DB 실행시간 | 역할+확인 약 58.2 ms | 세 종류 합계 약 13.3 ms | 이것은 **DB 서버 실행시간**이다. 클라이언트 왕복·Java 브로커 대기는 포함하지 않는다. |

이 값은 primary의 `pg_stat_statements`를 시험 전후에 읽은 **누적 카운터 차이**다. SQL 원문·DB 이름·주소는 저장 전에 제거했다. 전후 창에 백그라운드 쿼리도 일부 포함될 수 있고, 두 20명 회차의 시각이 완전히 동일하지 않아 정확한 push당 쿼리 횟수로 환산하지 않는다. 원본 비식별 카운터: [수정 전 시작](evidence/issue-378/gcp-before-n20-pre.json), [수정 전 종료](evidence/issue-378/gcp-before-n20-post.json), [수정 후 시작](evidence/issue-378/gcp-after-n20-pre.json), [수정 후 종료](evidence/issue-378/gcp-after-n20-post.json).

```text
그림 4 — 실제 ADMIN 회수와 두 판정 시각

동일 앱 VM 내부에서 구독 50개 CONNECT → SUBSCRIBE 완료
  ├─ 수정 전: 회수 전 MESSAGE 62건 기록
  └─ 수정 후: 회수 전 MESSAGE 608건 기록
       │
       ▼
시험용 operator JWT로 DELETE /admin/users/{id}/roles/ADMIN
  → DB 역할 삭제·커밋 → 권한 무효화 → HTTP 200 시각 기록
       │
       ├─ 발행 시각 > HTTP 200 + 1 ms : 새 판정 후보로 집계
       ├─ 발행 시각 < HTTP 200 - 1 ms, 수신은 뒤 : 늦은 수신으로 분리
       └─ ±1 ms : 경계 불명확으로 분리
       ▼
8초 추가 관측: 두 버전 모두 200 뒤 새 판정 후보 MESSAGE 0건
  · 각 50개 연결 종료가 관측됐다.

주의: 이 1회씩의 실측만으로 모든 네트워크·장애 타이밍에서
      '클라이언트 수신 0건'이라는 보편적 보장을 주장하지 않는다.
```

권한 회수 로그는 [수정 전](evidence/issue-378/gcp-app/before-revocation-events.jsonl)과 [수정 후](evidence/issue-378/gcp-app/after-revocation-events.jsonl)에 분리했다. 각 회차는 50구독 준비, 실제 회수 HTTP 200 1건, 200 이후 새 발행 후보 수신 0건이다. 연결 종료는 각각 50건 관측했지만 Python 클라이언트 예외 종류가 `ConnectionClosedError`로만 저장됐으므로 정상적 보안 종료와 수송 오류를 코드 없이 더 세분하지 않는다. 수정 전에도 0건인 이 시험은 **수정 후 안전성 회귀가 보이지 않았다는 관측**이지, 수정 전 코드가 모든 경합에서 안전하다는 증명은 아니다.

## 어디서 어떤 명령으로 확인했나

| 위치 | 명령·절차(주소·비밀 치환) | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 로컬 → GCP | `gcloud compute instances create <앱 VM>/<k6 VM> --machine-type=e2-standard-2 --image=<Rocky 9 x86_64>` | 두 VM `RUNNING`; 각 Rocky 9.8 `x86_64` | 기존 DB 3노드와 분리한 부하 환경 |
| 앱 VM | `java -version`, `systemctl is-active redis` | Java 17.0.20.1, Redis `active` | 앱·Redis 전제 확인 |
| 부하 VM | `k6 version` | 공식 k6 2.3.0 | 동일 도구로 전후 측정 |
| 두 VM | `chronyc burst 4/4` → `chronyc makestep` → `chronyc tracking` | 양쪽 `Leap status: Normal` | 발행→수신 지연의 음수 시계 샘플 0건 |
| 앱 VM | `git worktree add --detach <수정 전/후 커밋>` 및 동일 fixture SHA-256 대조 | 두 코드 커밋 고정, fixture 해시 일치 | 비교 중 변경 변수 축소 |
| 앱 VM | `./backend/gradlew -p backend testClasses` (버전별) | 두 버전 컴파일 성공 | 동일 publisher fixture의 실행 가능 상태 |
| 앱 VM | `./backend/gradlew -p backend dashboardCloudLoadTest -Ddashboard.load.runId=<run> ...` | 시험 사용자·JWT 생성, 300 ms 합성 발행, 종료 시 사용자 2명 삭제 | 앱·Hikari·OpenProxy·DB 경로 포함 |
| 부하 VM | `python3 k6_dashboard_capture.py --variant <before|after> --clients <1|5|20|50> --warmup-seconds <3|10> --measure-seconds <12|30> ...` | 각 버전 6개 유효 회차, 수정 전 스모크 2회 로그 수집 실패도 별도 보존 | 실시간 표본 허용목록 필터를 거친 k6 결과 |
| 로컬 → primary | `dashboard_pgstat_snapshot.py --run-id <전|후> --output <새 파일>` | 20명 조건의 전후 SQL 호출 증가량 위 표 | DB 호출 비용 변화 관측 |
| 앱 VM | `websocket_dashboard_revocation.py --clients 50 --before-seconds 3 --after-seconds 8` | 전·후 각 HTTP 200, 회수 후 새 후보 0건 | 실제 권한 회수 경로의 제한적 안전성 관측 |
| 앱 VM | fixture `stop` 신호 → `finished` 확인 | 버전별 시험 사용자 2명 정리 완료, JWT 파일 없음 | 테스트 데이터와 토큰 정리 |
| OpenSQL primary | 컨테이너 로그인 환경의 `psql`로 시험 이메일 패턴의 `count(*)`만 조회 | 잔여 사용자 **0명** | fixture 자체 기록과 독립적으로 DB 정리 확인 |
| GCP·로컬 | 허용목록 파일만 SCP, 주소·토큰·암호 패턴 검사 | 실행 증거 파일의 민감 패턴 0건. 본문에서 IP 형식과 겹치는 `Java 17.0.20.1` 두 곳은 수동 확인한 오탐 | 원시 URL·JWT를 저장하지 않음 |

```text
그림 5 — 로그가 기록되기 전 비밀정보 경계

k6 내부 원시 Point (URL·태그가 포함될 수 있음)
  → 메모리 FIFO 수신
  → 허용 metric 이름·숫자·KST 시각만 복사
  → 회차별 samples.jsonl / summary.json / manifest.json
  → 공개 전 주소·토큰·계정 패턴 검사

Spring fixture 내부 JWT/DB 암호
  → 0600 임시 파일·프로세스 환경에서만 사용
  → 앱 로그 원문은 공개 수집하지 않음
  → publisher-events.tsv에는 시각·순번·전송시간·예외 종류만 기록
  → 시험 종료 시 JWT·계정·DB 암호 복사본 삭제

첫 스모크 2회: summary는 있지만 samples=0 → 실패로 남김
파서 보정 후 새 run ID의 3회차부터 유효 결과로 채택
```

## 한계와 다음 판단

- 이 시험은 **1개 앱 VM, 1개 k6 VM, 최대 50 WebSocket 구독, 각 조건 30초**다. 두 백엔드 로드밸런싱, 장기 soak, OpenProxy·primary 장애, PDF·임베딩 동시 부하는 아직 포함하지 않았다.
- 정리 검증의 첫 `psql` 호출은 컨테이너 로그인 환경을 거치지 않아 기본 소켓 경로를 못 찾았다. 같은 SQL을 컨테이너의 `bash -lc` 환경에서 다시 실행해 잔여 0명을 확인했다. 첫 실패를 데이터 잔여로 해석하지 않는다.
- `pg_stat_statements`는 누적값이고 앱 외 쿼리도 섞일 수 있다. 쿼리 유형의 감소를 보여 주지만 각 프레임의 정확한 DB 쿼리 수와 인과를 1:1로 증명하지는 않는다.
- 권한 회수는 버전별 1회, 50구독으로 시험했다. 회수 전 판정 메시지의 네트워크상 늦은 수신과 모든 경합 스케줄을 다 뒤덮은 검증은 아니며, 별도의 결정적 경합 테스트 결과와 함께 해석해야 한다.
- 앱 JVM 원시 로그와 전체 스택 덤프는 비밀값·내부 주소가 섞일 수 있어 이 회차의 공개 증거로 저장하지 않았다. 기계적으로 허용목록 처리한 publisher·k6·DB·권한 이벤트만 보존했다.
- 두 시험 VM은 사용자의 후속 부하 시험을 위해 **중지·삭제하지 않았다**. Redis는 계속 `active`; 일회용 Java fixture는 종료했다. VM·부팅 디스크의 과금은 계속된다. 다음 사용 때 새 임시 자격 증명을 다시 안전하게 주입해야 한다.
