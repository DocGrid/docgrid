# OpenProxy 장애 직후 HTTP 500: 기존 원장 재분석과 원인 판정 범위

관련: [이슈 #393](https://github.com/DocGrid/docgrid/issues/393), [장애 부하 결과 PR #392](https://github.com/DocGrid/docgrid/pull/392).

**결론:** 과거 GCP A/B 프록시 장애 실행의 HTTP 500은 각각 **3건·8건**이며, A의 3건과 B의 5건은 장애 명령이 반환된 **후에 시작한 요청**이다. 따라서 “종료 전 이미 진행 중이던 요청만 실패했다”는 설명은 배제된다. 실패 요청의 DB 반영은 기존 요청 ID 대조에서 **0건**이었다. 그러나 요청별 앱 예외 체인·SQLSTATE·트랜잭션 단계가 없으므로 **죽은 Hikari 연결의 재사용, 인증 중 역할 조회, INSERT, 커밋 중 어느 단계가 원인인지 확정하지 못했다.** 이번 작업은 새 GCP 장애 시험이나 프로덕션 Fix가 아니다.

## 시험 범위와 기존 경로

```text
그림 1 — 동일한 HTTP 500으로 보일 수 있는 서로 다른 실패 단계

k6(요청 ID·시각 기록)
  │ POST /api/ha-probe/writes
  ▼
내부 LB → 앱 A/B
          │
          ├─ ① JwtAuthenticationFilter
          │     /admin/** 가 아니므로 getRoles() 사용
          │     Redis cache miss면 DB 권한 조회 가능
          │
          ├─ ② HaProbeWriteController.write()
          │     HaProbeWriteService.write() 호출
          │
          ├─ ③ @Transactional JPA INSERT → commit
          │     OpenProxy A/B 중 하나의 연결 사용
          │
          └─ ④ 예외가 DataAccessException이면
                GlobalExceptionHandler → HTTP 500

현재 원장에는 HTTP 상태와 DB 최종 행은 있지만 ①~③의
어느 경계에서 예외가 발생했는지는 기록되지 않았다.
```

`/api/ha-probe/**`는 ADMIN 권한이 필요하지만 JWT 필터의 강제 primary 판정 경로인 `/admin/**`는 아니다. 따라서 합성 요청의 Redis 역할 캐시가 빗나가면 쓰기 **전**에 DB 권한 조회가 일어날 수 있다. 반대로 캐시 적중 시에는 그 조회가 없다. 기존 원장만으로 각 실패 요청의 캐시 적중 여부를 알 수 없다. `HaProbeWriteService`의 트랜잭션은 서비스가 정상 반환된 후 컨트롤러가 201을 만들도록 구성돼 있다. 다만 오류 응답의 정확한 예외 발생 위치는 별도 문제다.

## 실행한 재분석

새 분석기 [`analyze_ha_http_failures.py`](../../scripts/opensql/analyze_ha_http_failures.py)는 기존 **비식별** `requests.csv.gz`의 허용된 열만 읽고, 요청 ID와 오류 사유를 출력하지 않는다. 각 실행의 `connection-recovery.json`에 저장된 KILL 명령 반환 시각을 기준으로 요청의 시작·완료 간격을 구하며, `reconciliation.json`과 500 건수가 다르면 실패시킨다. 원본의 물리적인 프로세스 종료 순간은 알 수 없으므로 이 시각을 **KILL 명령 반환 관측 마커**라고 부른다.

```text
그림 2 — 기존 요청 원장과 장애 마커를 다시 맞춘 방법

실행별 requests.csv.gz       connection-recovery.json
  sent_at / completed_at       kill_command_returned_at_utc
  outcome / http_status                   │
           └──────────────┬──────────────┘
                          ▼
              500 요청만 선택해 시간차 계산
              sent_at < marker / >= marker
              응답 완료 범위·요청 소요시간 범위
                          │
                          ▼
              reconciliation.json의 실패·DB 반영 수와 대조
                          │
                          ▼
              집계 숫자만 출력 (요청 ID·오류 문자열 제외)
```

| 별도 실행·위치 | 명령/방법 | 숫자로 확인한 결과 | 판정·개별 기록 |
| --- | --- | --- | --- |
| A 원장 재분석 · 로컬, `ha393-offline-a-20261003` | `python3 scripts/opensql/analyze_ha_http_failures.py --run-dir docs/test-results/evidence/issue-391/ha391fa20001` | 500 **3건**, 마커 전 시작 **0건**, 마커 후 시작 **3건**. 시작 **+32~+83ms**, 완료 **+94~+122ms**, 요청 소요 **39~62ms**. 실패 후 DB 반영 **0건** | **재분석 성공** · [실행 로그](evidence/issue-393/ha393-offline-a-20261003.md) |
| B 원장 재분석 · 로컬, `ha393-offline-b-20261003` | 같은 명령의 `--run-dir`을 `ha391fb20001`로 변경 | 500 **8건**, 마커 전 시작 **3건**, 마커 후 시작 **5건**. 시작 **-60~+40ms**, 완료 **+52~+125ms**, 요청 소요 **62~141ms**. 실패 후 DB 반영 **0건** | **재분석 성공** · [실행 로그](evidence/issue-393/ha393-offline-b-20261003.md) |
| 분석기 단위 시험 · 로컬, `ha393-python-20261003` | `python3 scripts/opensql/test_analyze_ha_http_failures.py -v` | **3/3 통과**. 마커 전/후, 원장 불일치 거부, 비허용 열 거부. 첫 호출 방식은 import 경로 오류로 **0건 실행 후 실패**, 파일 직접 실행으로 재시도 | [시험 로그](evidence/issue-393/ha393-python-20261003.md) |
| HTTP 예외 변환 단위 시험 · 로컬, `ha393-java-20261003` | `./backend/gradlew -p backend test --tests com.opensource.docgrid.global.exception.GlobalExceptionHandlerDataAccessTest --console=plain` | DB 연결 자원 예외를 넣으면 **HTTP 500**. Gradle 캐시 접근 제한으로 첫 시도는 시험 전 실패, 허용된 실행 환경에서 재시도 **BUILD SUCCESSFUL** | [시험 로그](evidence/issue-393/ha393-java-20261003.md) |

과거의 [A 요청 ID 대조](evidence/issue-391/ha391fa20001/reconciliation.json)와 [B 요청 ID 대조](evidence/issue-391/ha391fb20001/reconciliation.json)는 201 응답 누락·중복 0건, 500 후 반영 0건을 기록한다. 이번 재분석은 그 숫자를 다시 집계하고 시간축을 추가했을 뿐, 과거 GCP 요청을 다시 실행한 것은 아니다.

```text
그림 3 — 500이 “진행 중 요청만”의 문제가 아닌 이유

                    KILL 명령 반환 = t 0ms
                          │
 A: 3개 요청       시작 +32 ~ +83ms ───▶ 500 완료 +94 ~ +122ms
 B: 3개 요청       시작 -60 ~ -10ms ───▶ 500 완료 +52 ~ +89ms
 B: 5개 요청       시작   0 ~ +40ms ───▶ 500 완료 +91 ~ +125ms

관측: 종료 마커 뒤 시작한 요청도 500을 받았다.
가능한 설명: 앱 풀이 아직 죽은 소켓을 보유했거나 신규 연결/SQL 경로가
             전환 중 실패했을 수 있다.
금지할 결론: 요청별 소켓·SQLSTATE가 없는데 특정 단계나 드라이버 결함으로 확정.
```

## 왜 지금 자동 재시도 Fix를 넣지 않았나

```text
그림 4 — 쓰기 결과가 불명확할 때 무조건 재시도의 위험

요청 X → INSERT → DB commit ── 응답 전 연결 단절 ──▶ 앱/클라이언트에는 오류
               │
               └─ DB에는 X가 이미 1행 있을 수 있음

앱이 X를 무조건 재시도 → X가 2행이 될 수 있음
현재 ha_probe_writes는 의도적으로 (run_id, request_id) 고유 제약이 없다.
이전 실행에서 500 후 반영 0건은 관측값이지 모든 장애 유형의 보증이 아니다.

안전한 다음 단계:
요청별 예외 단계·SQLSTATE·커밋 상태를 비밀 없이 기록
  → 전/후 DB 대조
  → “실행 전 실패만 재시도” 또는 멱등 키 원자 처리 등 조건별 Fix 판단
  → 동일 GCP 부하·장애 재실행
```

즉, 500은 실제 가용성 결함으로 남아 있지만 **원인을 특정하지 못한 상태의 무조건적인 쓰기 재시도는 더 큰 데이터 정합성 결함을 만들 수 있다.** 현재 500을 503으로 이름만 바꾸는 것도 성공률을 개선하지 않고 클라이언트 재시도를 유발할 수 있어 적용하지 않았다.

## 증거 공백과 후속 작업

- 과거 앱 journal의 요청별 예외 체인·SQLSTATE가 저장소 원장에 없다. 이전 문서에 남은 `PSQLException` 포함 로그 행 수는 HTTP 500과 1:1 상관관계를 증명하지 않는다.
- 이번 조사에서 Cloud Logging의 해당 구간 앱 로그는 **0건**이었고, 기존 VM으로의 SSH 접근은 안전 심사에서 허용되지 않아 journal을 읽지 못했다. 우회 접근은 하지 않았다. [접근·증거 한계 기록](evidence/issue-393/ha393-access-20261003.md).
- 따라서 이슈 #393의 **요청별 장애 단계 규명**은 미완료다. 이 PR을 병합하더라도 이슈를 닫지 않는다. 승인된 원격 로그 접근이나 사전 비식별 단계 계측이 가능해지면 A/B 장애를 다시 실행하고, ① 필터·Redis ② JPA INSERT ③ 커밋 ④ Hikari 신규 연결 실패를 요청 ID별로 나눠 판정해야 한다.
- 실제 GCP 재실행과 Fix 전후 비교는 **이번 작업에서 수행하지 않았다**. 프록시·DB·앱 상태는 변경하지 않았다.
