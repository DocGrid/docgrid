# OpenSQL 계획 전환·OpenProxy 장애 직후 HTTP 500 요청별 원인 조사

관련: [이슈 #418](https://github.com/DocGrid/docgrid/issues/418), [기존 primary 부하 결과](issue-412-opensql-switchover-vu-telemetry-20261004.md), [기존 프록시 장애 결과](gimin-391-openproxy-ab-fault-load-20261003.md), [과거 오프라인 500 분석](gimin-393-openproxy-http-500-analysis-20261003.md).

## 결론

**두 장애를 같은 오류로 묶을 수 없다.** 2026-10-03 실제 GCP 시험에서 계획된 primary 전환 중 HTTP 500 **89건**은 앱 로그에서 트랜잭션 시작 단계 **79건**, SQL 실행 단계 **10건**으로 갈렸다. OpenProxy A 프로세스 중단 중 HTTP 500 **5건**은 모두 SQL 실행 단계였다. 모든 500을 합성 `request_id`로 실제 앱 진단 이벤트에 **1:1 대조**했다. 정상 기준선은 201 **201/201건**, 장애 실행은 각각 201 **12,511/12,600건**, **12,996/13,001건**이었다.

새 primary DB의 요청별 행 수 대조에서는 두 장애 모두 **201 응답 후 누락 0·중복 0, 500 응답의 DB 반영 0, 결과 불명 0**이었다. 이것은 *이번 실행의 관측값*이지 임의의 쓰기 자동 재시도가 항상 안전하다는 보장은 아니다. 앱 로직에 무조건 재시도를 넣는 `[Fix]`는 이번 결과만으로 정당화되지 않아 적용하지 않았다. 이 PR의 변경은 **시험 프로필 전용 상관관계 계측과 증거 검증**이다.

그림 1 — 두 장애의 위치와 공통 관측 경로

```text
GCP 부하 VM(k6) ── request_id + JWT ──▶ 내부 LB ──┬─▶ 앱 A ─ Hikari ┐
       │                                           └─▶ 앱 B ─ Hikari ┤
       │                                                             ▼
       │                                                    OpenProxy A / B
       │                                                             │
       │                  ① A 프록시 프로세스 KILL ─────────────────┤
       │                                                             ▼
       │                                                   OpenSQL 3노드 / Patroni
       │                  ② primary 계획 이전 ─────────────────────┘
       │
       └─ HTTP 201·500을 DB 밖 안전 이벤트에 실시간 기록
                              │
앱별 안전 진단 로그 ─ request_id┼─▶ 새 primary의 request_id별 행 수
                              ▼
               응답 ↔ 실패 단계 ↔ 최종 DB 결과를 사후 1:1 대조
```

프록시가 하나 죽는 시험과 DB 리더가 바뀌는 시험은 별도 실행 ID·별도 로그·별도 복구로 수행했다. DB VM 3대 중 2대에 OpenProxy가 있으며, 앱 VM 2대·공용 캐시 VM 1대·부하 VM 1대·내부 LB 1개를 사용했다. 이 시험은 HTTP 쓰기 probe 경로이고 실제 PDF 인덱싱 경로가 아니다.

## 왜 계측을 추가했나

과거 #415의 계획 전환에는 500 **85건**, #392의 프록시 A/B 중단에는 각각 **3/8건**이 있었지만, 당시 앱 오류 로그에 요청 ID가 없어 HTTP 원장과 예외를 1:1 연결할 수 없었다. 과거 로그 집계에서는 계획 전환 쪽의 트랜잭션 시작·Hikari 연결 대기 오류와 프록시 쪽 연결 I/O 오류를 봤다. 그러나 *시각과 건수가 맞는 것*만으로 어떤 요청이 어느 단계에서 실패했는지 확정할 수 없었다. 이번 89건·5건은 다른 시각·클러스터 timeline·진단 JAR의 새 실행이므로 과거 85건·3/8건과 증감률로 비교하지 않는다.

| 구성요소 | 이번 변경 | 이유·경계 |
| --- | --- | --- |
| `ha_probe_load.js` | 합성 실행·요청 ID를 별도 헤더에 넣음 | JWT·내부 주소를 로그에 복사하지 않고 요청을 앱 로그와 결합 |
| `HaProbeDiagnosticFilter`·`HaProbeDiagnosticContext` | 시험 프로필의 probe 경로에서만 ID·단계·상태를 MDC에 유지; 종료 시 제거 | 인증 이전 실패와 thread 재사용 누출 구분. ID 형식 제한·원문 예외 배제 |
| `HaProbeWriteController`·`HaProbeWriteService` | `TX_BEGIN` → `SQL_EXECUTE` → `COMMIT_PENDING` 단계 지정 | 트랜잭션 프록시가 서비스 메서드 전에 실패하는 경우를 SQL 실행 실패와 분리 |
| `HaProbeDiagnosticAdvice` | 해당 컨트롤러의 409·500 응답 계약을 유지하며 고정 필드만 남김 | SQL URL·메시지를 새 증거 로그에 복사하지 않고 재시도도 하지 않음 |
| `sanitize_ha_probe_journal.py` | 앱 VM 안에서 systemd 원문을 고정 필드 JSONL로 변환 | 비공개 호스트·IP·예외 원문이 증거로 반출되기 전에 제거 |
| `analyze_ha_probe_diagnostics.py` | 앱 A/B 이벤트와 외부 HTTP 원장을 ID로 조인 | 500과 앱 예외의 미매칭·단계별 숫자를 계산 |

그림 2 — 앱 안에서 단계가 정해지는 지점

```text
POST /api/ha-probe/writes
   │
   ├─ HaProbeDiagnosticFilter: 헤더 형식 검사 → MDC(run, request, AUTHORIZATION)
   │        │
   │        └─ Spring Security: JWT/ADMIN 검사
   │
   └─ HaProbeWriteController.write()
            │ phase = TX_BEGIN
            ▼
       @Transactional 프록시: Hikari 연결 획득·BEGIN
            │ 여기서 예외 → TX_BEGIN (이번 switchover 79건)
            ▼
       HaProbeWriteService.write()
            │ phase = SQL_EXECUTE
            ├─ INSERT 실패 → SQL_EXECUTE (switchover 10건, 프록시 KILL 5건)
            └─ INSERT 반환 → phase = COMMIT_PENDING → 트랜잭션 COMMIT
                                      │
                                      ├─ 성공 → HTTP 201
                                      └─ 실패 → DB 밖 요청 원장으로 최종 행 수 확인

응답·예외 처리 뒤 filter.finally: MDC 제거
```

이 분류는 **마지막으로 통과한 코드 경계**다. `SQL_EXECUTE`는 INSERT 중 실패를 말하며, 내부 네트워크 예외가 어느 TCP 패킷에서 발생했는지는 뜻하지 않는다. `sqlstate=none`은 오류가 없다는 뜻이 아니라 이 안전한 원인 체인에서 SQLSTATE를 추출하지 못했다는 뜻이다.

## 실행 환경·방법

| 항목 | 실제 사용·검증 |
| --- | --- |
| GCP 시험 자원 | DB VM 3대, 그중 프록시 2개, 앱 VM 2대, 공용 캐시 VM 1대, 부하 VM 1대, 내부 LB 1개 |
| 안전 게이트 | Patroni leader 1·streaming replica 2/lag 0 MB, etcd 3/3, 앱 LB 2/2, 두 프록시 ready, DB snapshot 존재; VM 전체 복원 리허설은 이번에 하지 않음 |
| 앱 배포 | 동일 진단 JAR SHA-256 `27db8d6a…7a34`를 A/B 순차 배포·health 확인; 시험 뒤 원래 JAR SHA-256 `c0180191…d1aa`로 A/B 복원 |
| 부하 | GCP 내부 k6 `constant-arrival-rate`; 정상 10 req/s × 20초, 전환 70 req/s × 180초(초기 VU 400), 프록시 100 req/s × 130초(초기 VU 40) |
| 독립 원장 | k6의 실시간 안전 이벤트 → 종료 뒤 `ha_evidence.py`로 재생·검증 → 새 primary의 `request_id` 행 수 export·조인 |
| 개인정보·비밀 | 합성 계정 4개와 20분 JWT 사용. 주소·토큰·프로젝트 ID·호스트 이름은 원본 증거 파일에 기록하지 않음 |

세 실행의 상세 명령·숫자·원본 링크는 [정상 기준선](evidence/issue-418/ha418base0001/실행-결과.md), [계획 전환](evidence/issue-418/ha418sw00001/실행-결과.md), [프록시 A 장애](evidence/issue-418/ha418px00001/실행-결과.md)에 나눴다. 허용 설정과 스크립트 해시는 [비식별 설정](evidence/issue-418/config-safe.json)에 있다. 설치 바이너리 버전은 이번 실행에서 재조회하지 않아 새 증거의 `manifest.versions`를 `not-rechecked`로 남겼다.

| 실행 | 전송 | 201 | 500 | 미전송/결과 불명 | 201 누락/중복 | 500 DB 반영 | 전체 p95/p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 정상 `ha418base0001` | 201 | 201 | 0 | 0/0 | 0/0 | 0 | 65.81/502.44 ms |
| 계획 전환 `ha418sw00001` | 12,600 | 12,511 | 89 | 0/0 | 0/0 | 0 | 66.74/4,718.19 ms |
| 프록시 A KILL `ha418px00001` | 13,001 | 12,996 | 5 | 0/0 | 0/0 | 0 | 51.46/52.73 ms |

그림 3 — planned switchover의 오류 경계

```text
18:48:10Z  k6 시작 ───────────── 70 req/s, 초기 VU 400
18:49:29.829Z       운영자가 Patroni switchover 명령 시작
18:49:44.125Z       첫 HTTP 500 요청 시작 ┐
18:49:45.925Z       switchover 명령 성공   │ 89개 500
18:49:52.199Z       마지막 500 응답 완료 ┘ 오류 경계 8.074초
18:49:52.262Z       그 이후 새 201 응답 관측
18:51:12Z  k6 종료 ───────────── 미전송 0, 201 요청 DB 누락 0

실패 89건 = TX_BEGIN 79 + SQL_EXECUTE 7(상위 JPA, SQLSTATE 없음)
                         + SQL_EXECUTE 3(SQLSTATE 25006)
검증 종료: leader 1 / streaming 2 / timeline 12 / lag 0 MB
```

세 건의 SQLSTATE `25006`은 읽기 전용 트랜잭션 오류다. 도착 노드별 쿼리 로그를 이번 실행에서 수집하지 않았으므로 **그 세 건이 반드시 어느 standby로 갔다고 단정하지 않는다.** 트랜잭션 시작 79건의 내부 원인은 이 계측만으로 Hikari 대기·풀 고갈·접속 실패 중 한 가지로 확정하지 않는다. 다만 과거 앱 로그에서 연결 대기와 트랜잭션 시작 예외가 관찰됐다는 사실과 구분해 제시한다.

그림 4 — 프록시 프로세스 중단의 오류 경계

```text
18:55:48.282Z  독립 자동 복구 타이머 무장
18:56:06Z      k6 시작 ─────────────────────────── 100 req/s
18:57:07.275Z  OpenProxy A KILL 명령 반환, B는 중단하지 않음
18:57:07.283Z  첫 500 요청 시작 ┐
18:57:07.396Z  마지막 500 완료 ┘ 5건, 오류 경계 113 ms
18:57:07.468Z  이후 새 201 응답 관측 ── 살아 있는 경로
18:57:55.318Z  A ready 재확인 ── 정확한 복구 순간은 미계측
18:58:17Z      k6 종료 ──────── 201 요청 DB 누락·중복 0

실패 5건 = 앱 A 1건 + 앱 B 4건 = 모두 SQL_EXECUTE/JpaSystemException
내부 SQLSTATE: none (원인 미추출), 외부 원장↔앱 이벤트 미매칭: 0
```

이번 실행은 프로세스 KILL+자동 복구이며 패킷 DROP이나 graceful shutdown의 검증이 아니다. KILL 이후 201이 빠르게 나온 것은 다른 프록시로 응답 가능한 경로가 남았다는 뜻이지, A가 193 ms 안에 회복됐다는 뜻이 아니다.

그림 5 — 왜 500에 무조건 자동 재시도를 붙이지 않았나

```text
클라이언트 원장               앱·DB 측 최종 상태
201 + request_id ──────────▶ DB 1행   → 이번 실행에서 보존
500 + request_id ──────────▶ DB 0행   → 이번 실행에서 실패 확인
시간 초과/연결 단절 ─────────▶ DB ?행   → 다른 장애에서는 커밋 결과 불명 가능
                                    │
                         고유 idempotency key 없는 즉시 재시도
                                    ▼
                         1행일 때 다시 INSERT하면 중복 위험

따라서: 실패 단계 계측은 추가했지만 자동 재시도는 추가하지 않음.
추후 재시도 Fix는 비즈니스 쓰기마다 고유 키·중복 응답 계약이 정해진 뒤 별도 시험.
```

## 검증·정리·남은 한계

| 위치·명령 또는 절차 | 관측 | 해석 |
| --- | --- | --- |
| 로컬 `./backend/gradlew -p backend test --tests …` | 관련 Java **12/12 통과** | 시험 프로필 게이트·관리자 인가·안전 로그·409/500 응답·단계 표시 확인. 전체 Gradle suite는 미실행 |
| 로컬 `python3 -m unittest discover -s scripts/opensql -p 'test_*ha_probe*.py' -v` | **4/4 통과** | 저널 비식별과 요청 ID 조인 합성 검증 |
| 로컬 `node --check scripts/opensql/ha_probe_load.js` | 통과 | k6 JS 문법 검사. k6 실실행은 위 GCP 부하 세 건 |
| GCP 내부 k6·원장 `ha_evidence.py verify` | 세 실행 모두 원장 재생 성공 | 안전 이벤트는 부하 중 기록했고 저장소 원장은 **사후** 가져와 재생 |
| 새 primary `export_ha_probe_counts.sh` + `reconcile_ha_probe.py` | 세 실행 모두 request_id 대조, 누락·중복 0 | 장애 두 실행의 `normal_baseline_pass=false`/종료 코드 2는 HTTP 500을 포함하기 때문 |
| 앱 A/B `journalctl -o json | sanitize_ha_probe_journal.py` | 계획 전환 178개, 프록시 장애 10개 안전 이벤트 | 실패당 EXCEPTION 1+RESULT 1. 원문 journal은 반출하지 않음 |
| 종료 복구 | 원래 JAR A/B, LB 2/2, DB leader 1·streaming 2/lag 0, etcd 3/3, 합성 계정 0/3노드, A 프록시 ready·가드 해제, 임시 JWT·SSH 키 제거 | VM은 계속 실행. 프로젝트 SSH 공통 메타데이터는 원래 4줄과 정확히 일치하도록 복구 |

실행 중 첫 로컬 테스트 시도는 Gradle 캐시 파일 접근 제한으로 시작조차 못했고, 형식 제한을 강화한 직후 한 테스트는 기존 테스트 데이터의 `run-1`이 새 합성 ID 규칙과 맞지 않아 실패했다. 테스트 데이터를 교정한 뒤 **최종 Java 12/12**, Python 4/4를 다시 실행했다. 이 준비 실패를 클러스터 결함으로 해석하지 않는다. [로컬 시도 기록](evidence/issue-418/local-tests.md)에 구분해 남긴다.

이 시험에서 *HTTP 500의 요청별 실패 단계*는 확정했지만, `JpaSystemException`의 하위 소켓 원인, 프록시 A의 정확한 복구 순간, 장애 중 새 연결이 B로 이동한 TCP 순간은 새 계측으로 확인하지 않았다. #392의 소켓 표본은 다른 실행이므로 이 실행의 정밀 타임스탬프로 재사용하지 않는다. 이 두 유형을 완전히 없애려면 쓰기별 멱등성·재시도 경계·503 매핑을 별도 설계하고 같은 조건에서 재측정해야 한다.
