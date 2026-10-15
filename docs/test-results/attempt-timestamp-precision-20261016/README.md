# Attempt 시각 정밀도 회귀 검증 결과

**수정 전 동일 시험 1건 실패, 수정 후 동일 시험 1건 통과를 확인했습니다. 관련 통합·서비스 단위 테스트 24건도 모두 통과했습니다.**

- 이슈: [#511](https://github.com/DocGrid/docgrid/issues/511)
- Fix PR: [#512](https://github.com/DocGrid/docgrid/pull/512)
- [시험 소스](https://github.com/DocGrid/docgrid/blob/0aecacd00688eda62f2737443c7a92d1f33ad30e/backend/src/test/java/com/opensource/docgrid/domain/embedding/integration/EmbeddingJobAttemptIntegrationTest.java#L269-L318)
- [검증한 서비스 코드](https://github.com/DocGrid/docgrid/blob/0aecacd00688eda62f2737443c7a92d1f33ad30e/backend/src/main/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobAttemptService.java#L50-L69)

## 1. 환경과 시험 조건

| 항목 | 실제 실행 조건 |
|---|---|
| 실행 위치 | 로컬 macOS arm64, Temurin 17.0.18 |
| DB | Docker의 실제 PostgreSQL 17.8 (Debian 17.8-1.pgdg12+1), pgvector 0.8.1 |
| 실행 시각 | 2026-10-16 03:24:09–03:26:25 +09:00 (Asia/Seoul) |
| 고정 Clock 입력 | `2026-10-16T00:00:00.123456789Z`, UTC. 시험 입력이며 실제 실행 시각과 다름 |
| 격리 | 실행 Context마다 UUID를 붙인 새 Schema. 시험 종료 뒤 접두 Schema 수 0건 확인 |
| 수정 전 서비스 | `8be67c36c387e6eacdab461274b21472f615cd3f`의 서비스 파일만 복원 |
| 수정 후 서비스 | 기존 #512의 마이크로초 정규화 코드. 이번에 운영 코드를 추가 변경하지 않음 |
| 테스트 소스 | 세 실행 모두 동일 SHA-256: `8cbda299d67207d12e78b67b44a8d5e91214be94125235d2f2c45659cf273f23` |

실행 당시 HEAD는 `560c4f7`이며 테스트는 작업 트리에 추가한 상태였습니다.
수정 전 실행만 서비스 파일을 위 develop 커밋의 내용으로 바꿨습니다.
시험 뒤 동일한 테스트 파일을 `0aecacd00688eda62f2737443c7a92d1f33ad30e`에 커밋했습니다.
각 실행의 실제 입력 서비스·테스트 SHA-256을 `run.json`에 별도로 남겼습니다.

## 2. 실행 결과와 증거

| 실행 ID | 목적·명령 | 실행·실패·오류·건너뜀 | 총 소요 | 판정 | 개별 증거 |
|---|---|---|---|---|---|
| red-01 | 수정 전, 명령 A | 1·1·0·0 | 17.642초 | 의도한 응답 동일성 단언 실패 재현 | [로그](red-01/live.log), [실행 정보](red-01/run.json), [JUnit 요약](red-01/junit-summary.json) |
| green-01 | 수정 후, 동일 명령 A | 1·0·0·0 | 16.595초 | 통과 | [로그](green-01/live.log), [실행 정보](green-01/run.json), [JUnit 요약](green-01/junit-summary.json) |
| regression-01 | 관련 클래스 전체, 명령 B | 24·0·0·0 | 17.132초 | 통합 7건·서비스 단위 17건 모두 통과 | [로그](regression-01/live.log), [실행 정보](regression-01/run.json), [JUnit 요약](regression-01/junit-summary.json) |

총 소요는 Gradle 프로세스 실행부터 종료 후 요약 수집까지입니다. 테스트 메서드별 소요는 JUnit 요약에 별도 기록했습니다.
수정 전 실행의 종료 코드 1은 예상한 결함 재현이며, 설정 오류를 통과 증거로 처리하지 않았습니다.
추가 재시도 실행은 없었습니다. 세 실행 모두 누락·건너뜀 표본은 0건입니다.

**명령 A: 신규 회귀 시험 1건**

```bash
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.EmbeddingJobAttemptIntegrationTest.start_replaysSameBodyAfterDatabaseRoundTrip_withSubMicrosecondClock' \
  --no-daemon --rerun-tasks --console=plain
```

**명령 B: 관련 기존 시험 포함**

```bash
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.EmbeddingJobAttemptIntegrationTest' \
  --tests 'com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptServiceTest' \
  --no-daemon --rerun-tasks --console=plain
```

기본 test 프로필의 실제 DB를 사용했습니다. 인증 설정은 시험 전용 값을 사용하고 상속 환경을 제한했습니다.
각 명령은 별도의 실행 ID·로그·요약 파일로 보존했습니다.

## 3. 시험이 실제로 확인한 흐름

```text
그림 1 — 생성·Commit·DB 조회·재전송

시험 메서드
  Clock.fixed(2026-10-16T00:00:00.123456789Z, UTC)
    → 주입 Clock의 값과 나노초 하위 자릿수 확인
    → 같은 Clock 기준으로 유효한 Worker·Claim·5분 Lease 준비
        │
        ▼
Transaction 1: inNewTransaction(REQUIRES_NEW)
  SELECT txid_current() → Transaction 식별 확인
  EmbeddingJobAttemptService.start(jobId, request)
    → Job 행 잠금 → 소유권 검증
    → 현재 Claim의 Attempt 없음 → create()
    → 최초 응답 DTO 반환
  COMMIT
        │
        ▼
JdbcTemplate: 저장된 Attempt.started_at 직접 조회
        │
        ▼
Transaction 2: inNewTransaction(REQUIRES_NEW)
  SELECT txid_current() → Transaction 1과 다른 식별 확인
  start(같은 Job·Worker·Claim)
    → 기존 Attempt 조회 → replay() → 재전송 DTO
        │
        ▼
응답 DTO 전체 동일성 + DB 시각 동일성 + Attempt 1건 단언
```

첫 Transaction이 끝난 뒤 DB를 직접 읽고, 두 번째 Transaction을 새로 열었습니다.
두 Transaction의 식별자가 다르다는 단언으로 같은 영속성 컨텍스트의 1차 캐시만 읽는 시험이 아님을 확인했습니다.

```text
그림 2 — 수정 전·후 시각의 실제 관측

고정 Clock 입력: 00:00:00.123456789 (UTC)
                   │
       ┌───────────┴────────────────┐
       ▼                            ▼
수정 전 서비스                  수정 후 서비스
now(clock)                      now(clock).truncatedTo(MICROS)
  │                               │
최초 DTO: .123456789             최초 DTO: .123456
  │                               │
PostgreSQL 저장                  PostgreSQL 저장
  │                               │
DB 값: .123457                   DB 값: .123456
  │                               │
replay DTO: .123457              replay DTO: .123456
  │                               │
전체 DTO 비교 실패               전체 DTO 비교 통과

두 실행 공통: Attempt 1건 / 독립 Transaction 확인
```

이 입력에서 PostgreSQL 저장값은 `.123457`로 반올림됐습니다.
수정은 DB에 전달하기 전에 마이크로초 단위로 정규화해 최초 DTO와 재조회 DTO가 같은 값을 사용하도록 합니다.
관측된 문제는 응답 시각 불일치이며, 중복 Attempt 생성이나 데이터 유실이 아닙니다.

```text
그림 3 — 변경 변수와 기존 동작 회귀 확인

동일 테스트 소스 + 동일 Clock + 동일 PostgreSQL
  │
  ├─ red-01
  │    수정 전 서비스만 사용
  │    → 시각 불일치로 1건 실패 / 오류 0건
  │
  ├─ green-01
  │    정밀도 수정 서비스만 복원
  │    → 같은 시험 1건 통과
  │
  └─ regression-01
       수정 후 코드 + 기존 시험 전체
         → 통합 7건: Migration·제약·Repository·행 잠금·멱등·동시성·신규 시각 시험
         → 서비스 단위 17건: 정상 생성·재전송·소유권·Lease·오류 경로
         → 합계 24건 통과 / 실패·오류·건너뜀 0건

각 실행 종료 → Schema DROP → 남은 격리 Schema 0건 확인
기존 로컬 PostgreSQL·Valkey 컨테이너는 종료하지 않음
```

고정 Clock은 이 테스트 Context에만 주입되며, 기존 통합 시험도 함께 실행했습니다.
회귀 시험으로 재현한 실패가 수정 효과와 직접 연결되고 기존 Attempt 동작도 유지되는지 확인했습니다.

## 4. 변경 파일과 역할

| 파일·구성 | 역할 |
|---|---|
| `EmbeddingJobAttemptIntegrationTest` | 고정 Clock Bean, 실제 DB 왕복 회귀 시험, Context별 격리 Schema |
| `docs/design/gimin-#511-attempt-timestamp-precision.md` | 검증 완료 상태와 결과 문서 연결 |
| 이 디렉터리의 README | 비교 요약·재현 명령·범위 설명 |
| 실행별 `live.log`, `run.json`, `junit-summary.json` | 개별 실행의 상태·입력 해시·관측값·시험 판정 보존 |

## 5. 로그 정책과 검증 범위

- Gradle 상태 줄은 수신 즉시 저장했습니다. 전용 시각·건수 증거 줄은 실행 종료 후 JUnit XML의 `system-out`에서 추출했습니다.
  `live.log`의 시각은 수집 시각이며, 전용 증거를 실시간 수신했다고 해석하지 않습니다.
- 로그 저장 전에 Gradle 상태와 전용 숫자·시각 형식만 허용했습니다. 그 밖의 출력은 저장하지 않았으며 생략 줄 수를 실행 정보에 기록했습니다.
- 전체 JUnit XML·HTML을 공개 증거에 복사하지 않고, 안전한 건수·단언 시각·전용 관측만 담은 구조화 요약을 보존했습니다.
- 실제 PostgreSQL을 사용했지만 로컬 시험입니다. GCP OpenSQL HA와 운영 트래픽 시험은 실행하지 않았습니다.
- 비교 대상은 Service가 반환한 응답 DTO 전체입니다. 실제 HTTP 직렬화·전송을 포함한 E2E 시험은 실행하지 않았습니다.
- 이번 실행 범위는 신규 회귀 시험과 관련 두 클래스입니다. 전체 백엔드 테스트·OS 행렬·CI 3회 합격을 완료했다고 주장하지 않습니다.
