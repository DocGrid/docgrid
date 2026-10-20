# OpenSQL·원본 OpenProxy·JDBC의 Generic Plan 자연 전환과 수정 효과

- 관련 PR: [#550 SQL 수정](https://github.com/DocGrid/docgrid/pull/550) · [#568 OpenSQL·JDBC 후속 시험](https://github.com/DocGrid/docgrid/pull/568)
- 제품 SQL: [시험 기준 findTopK](https://github.com/DocGrid/docgrid/blob/b6ee78bfebfcc8d23b4ee039e2852faf6679fb98/backend/src/main/java/com/opensource/docgrid/domain/search/repository/VectorSearchRepository.java#L23-L43)
- 기준 스키마: [a24d3e8 migration](https://github.com/DocGrid/docgrid/tree/a24d3e80771168cdc95c67c2ef8ad51cf2130241/backend/src/main/resources/db/migration)

## 결론

2026-10-21 KST, 기존 약 5만 청크 재현 조건을 실제 클라우드 OpenSQL·원본 OpenProxy 바이너리·pgJDBC 경로에 맞췄습니다.
`plan_cache_mode=auto`에서 자연 Generic Plan 선택과 수정 전 SQL의 지연 급증이 관측됐습니다.
기존 #550 수정 SQL은 Generic Plan에서도 같은 급증을 보이지 않았으며, 최종 유효 **120/120회**의 검색 결과가 일치했습니다.

| 경로 | 수정 전 Generic p50 | 수정 후 Generic p50 | Generic 표본 수 | 결과 일치 |
|---|---:|---:|---:|---:|
| Proxy A · R2 | 9,755.10ms | 977.76ms | 각 SQL 16회 | 전체 60/60회 |
| Proxy B · R1 | 9,880.39ms | 1,016.81ms | 각 SQL 16회 | 전체 60/60회 |

위 값은 **로컬 클라이언트에서 IAP·SSH·Proxy를 거친 JDBC 실행·결과 수신 시간**입니다.
HTTP 사용자 응답시간, 공개 리스너 또는 Spring/Hikari 경로의 성능이 아닙니다.
A/B는 프록시 경로 별칭이며 백엔드 앱 두 대를 비교한 시험이 아닙니다.
제품 코드 변경 없이 기존 수정의 적용 범위를 확인한 후속 시험입니다.

## 목적

- [#545 기준 데이터](../gimin-%23545-permission-filter-vector-recall.md)와 [#549·PR #550 수정](../gimin-%23549-generic-plan-query-vector-cast.md)의 Generic Plan 문제가 실제 OpenSQL·OpenProxy·JDBC에서도 발생하는지 확인합니다.
- 같은 데이터·인덱스에서 수정 전후 SQL만 바꿔 지연과 결과 일치를 확인합니다.
- 각 경로에서 SQL별 30회, 총 60회를 수집하고 서버 카운터 증가량으로 계획을 분류합니다. 결과 40행·순서 포함 해시 일치, 별도 실행계획 수집, 자원 정리를 완료 기준으로 삼았습니다.
- Generic Plan 선택 자체는 오류가 아닙니다. 수정 전 SQL이 그 계획에서 느려지는 현상과 기존 수정의 효과가 검증 대상입니다.

## 테스트 환경

### 기존 로컬 측정과의 구분

| 구분 | 기존 로컬 성능 측정 | 이번 OpenSQL·JDBC 검증 |
|---|---|---|
| DB | 로컬 격리 PostgreSQL 17.8·pgvector 0.8.1 | 실제 클라우드 OpenSQL의 PostgreSQL 17.8·pgvector 0.8.1 |
| 호출 | 서비스 메서드 단위, 질문 30개 | 원본 OpenProxy 별도 리스너를 거친 JDBC, D01 1개 반복 |
| 계획 | 대표 성능표는 `force_generic_plan` | `auto`, Generic Plan 강제 없음 |
| 결과 | 허용 100%·topK 10 연속 측정에서 벡터 p50 4,465.7→218.5ms, 하이브리드 5,799.9→1,380.6ms | 경로별 수정 전후 30회 중 Generic 표본 각 16회, 위 표의 왕복 시간 |
| 해석 | 로컬 검색 단계의 개선 | 원본 제품 Proxy·JDBC를 통한 자연 전환과 SQL 수정 효과 확인 |
| 공통 한계 | 전체 HTTP 사용자 응답시간이 아님 | 전체 HTTP 사용자 응답시간이 아님 |

서로 다른 장비·표본 구성·호출 경로이므로 두 표의 절대 시간을 직접 비교하지 않습니다.
기존 로컬 30문항 성능표를 이번 클라우드 값으로 대체하지 않습니다.

### 재현 조건과 출처

| 구성 | 실제 사용 조건·확인한 범위 |
|---|---|
| 시험 리비전 | 제품 SQL 추출 기준 `b6ee78bfebfcc8d23b4ee039e2852faf6679fb98`; 이번 PR의 최신 코드로 다시 시험한 것이 아님 |
| 문서 | `ed5d601`의 팀 작성 원본 251건, SHA-256 251/251 일치 |
| 벡터 | 기존 BAAI/bge-m3 1,024차원 캐시·실제 3,572청크를 재사용. 모델 API 새 호출 없음 |
| 확장 | 기존 `PermissionRecallDataset`을 변경 없이 사용, Seed 545, 기준 문서 간 유사도 p50/p90 사용 |
| 최종 규모 | 문서 3,559개·청크 50,008개, 합성 청크 46,436개(92.857%) |
| 질문·권한 | 기존 재현 질문 D01, 허용 문서 100%(3,559개), topK 10에 대응하는 SQL 후보 상한 40 |
| 스키마 | 기존 `a24d3e8` 기준 V1~V52 및 R seed SQL. 원래 FK·인덱스를 적용한 별도 논리 DB·스키마 |
| 스키마 적용 방식 | SQL을 직접 실행. Flyway 실행기·이력 테이블 검증은 아님. V28 관리자 seed는 비식별 시험 값으로 대체하고 적재 전 users 등을 초기화 |
| HNSW | 동일 cosine 정의, 크기 409,673,728바이트. 물리 인덱스 파일이나 그래프의 바이트 동일성을 확인한 것은 아님 |
| 클라이언트 | Java 17·pgJDBC 42.7.11, `prepareThreshold=5`, 같은 JDBC 연결과 두 PreparedStatement 유지 |
| 원본 Proxy | 기존 설치 바이너리, 별도 시험 데이터·관리 포트. 앱 풀의 transaction pooling·파서·읽기 라우팅·캐시·서버 정책 유지. 역할 어댑터 없음 |
| 분리 범위 | 전용 논리 DB와 시험 리스너. 물리 DB VM·CPU·스토리지는 기존 클러스터와 공유 |
| 부하 | 동시성 1, 수정 전→수정 후 교차 실행 각 30회. 바인딩 매개변수 3,563개 |
| 관측 | 읽기 전용 트랜잭션마다 스키마·30초 제한 설정. 실제 standby backend 2개 관측 |
| 제외 경로 | 공개 리스너·Spring/Hikari·HTTP·브라우저·하이브리드·RAG·모델 API·장애/승격 시험 |

캐시 및 스키마 출처는 [fixture-provenance.json](fixture-provenance.json), [schema-manifest.json](schema-manifest.json)에 있습니다.
`fixture-provenance.json`의 `target_scale` 시간·용량은 **기존 로컬 기준 결과**입니다.
이번 적재 시간·건수·크기는 [별도 적재 로그](GP50-FIXTURE-20261021-R1-events.jsonl)의 시각과 완료 이벤트를 사용합니다.
이번 HNSW 크기는 409,673,728바이트, embeddings 전체는 700,620,800바이트, 적재 완료 시 DB 크기는 832,804,531바이트였습니다.

```text
그림 1 — 실제 실행 위치와 격리 경계

로컬: 기존 문서·Embedding 캐시
  ├─ 원본 SHA 251/251 확인
  └─ Dataset(Seed 545) → 3,559문서·50,008청크
                             │ 적재는 Primary 직결
                             ▼
클라우드 OpenSQL: 전용 논리 DB·시험 스키마
  ├─ 기존 DDL·FK·HNSW → COPY·ANALYZE·COMMIT
  └─ 물리 호스트는 기존 클러스터와 공유
                             ▲
                             │ 읽기 전용 검색
로컬 Stage50 → pgJDBC → IAP/SSH → 원본 OpenProxy 별도 리스너
                                  ├─ 경로 A: 서버 backend 2개
                                  └─ 경로 B: 서버 backend 2개

공개 리스너·개인 환경 보존 / 역할 어댑터 없음
API 서버와 브라우저는 이번 계측에 포함하지 않음
```

이는 클라우드의 실제 DB·원본 Proxy 바이너리를 사용하되 공개 경로와 분리한 SQL 검증입니다.

## 테스트 방법

### 실행별 목적·시간·판정

모든 시각은 2026-10-21 KST(UTC+09:00)입니다. 표본 구간과 준비·후처리 시간을 구분합니다.

| 실행 ID | 실행 위치·절차 | 실제 구간·표본 | 판정·용도 |
|---|---|---|---|
| `GP50-PREFLIGHT-20261021-R1` | 로컬 기준 기록·DB VM 읽기 전용 조회, 원본 SHA·캐시·공간 점검 | 07:42:12~07:42:28 | 준비 통과, 기준 규모·원본 일치 |
| `GP50-FIXTURE-20261021-R1` | 전용 DB에 기준 스키마·캐시·합성 데이터 적재 | [이벤트별 시각](GP50-FIXTURE-20261021-R1-events.jsonl), 실제 적재 1회 | 3,559문서·50,008청크 완료 |
| `GP50-PROXY-A-RO-20261021-R1` | 로컬 JDBC→격리 Proxy A→OpenSQL, 검색 뒤 계획 출력 | 검색 60회, 후처리 미완료 | 최종 비교 제외, [원본 보존](GP50-PROXY-A-RO-20261021-R1-events.jsonl) |
| `GP50-MASK-DIAG-20261021-R1` | 로컬 Java에서 3,559개 매개변수 배열 마스킹 비교 | 07:59:54~07:59:55 | 기존 StackOverflowError 재현, 수정 표현 완료 |
| `GP50-PROXY-B-RO-20261021-R1` | 보완한 수집기로 경로 B 측정 | 표본 08:01:09.755543~08:05:51.957119, 60회 | 최종 유효, [회차 요약](GP50-PROXY-B-RO-20261021-R1-summary.md) |
| `GP50-PROXY-A-RO-20261021-R2` | 같은 수집기로 경로 A 재측정 | 표본 08:07:05.003513~08:11:42.198558, 60회 | 최종 유효, [회차 요약](GP50-PROXY-A-RO-20261021-R2-summary.md) |
| `GP50-DB-CLEANUP-20261021-R1` | 이번 전용 DB만 삭제 | 08:12:13~08:12:21 | 카탈로그 잔여 DB 0개 |
| `GP50-PRESERVE-20261021-R1` | 원격 프로세스·설정과 로컬 환경 해시 대조 | 08:12:37~08:12:51 | 시험 자원 제거·기존 상태 보존 |

### 호출과 계획 분류

1. `VectorSearchRepository.findTopK`의 실제 SQL을 추출했습니다. 수정 후 SQL의 SHA-256은 `4c65c899327a380a473a9a20b37bb12df8949ce551d3410f7434b34babd1dd53`입니다.
2. 수정 전 SQL은 두 `(SELECT CAST(:queryVector AS vector))`만 `CAST(:queryVector AS vector)`로 치환했습니다. 다른 절·바인딩·데이터·인덱스는 유지했습니다.
3. 같은 연결에 두 PreparedStatement를 유지하고, 각 호출을 읽기 전용 트랜잭션으로 실행했습니다. `SET LOCAL search_path`로 transaction pooling의 스키마 경계를 명시했습니다.
4. 검색 직전·직후의 backend 별칭이 같은지 확인했습니다. 검색 실행과 결과 40행 수신을 타이머에 포함하고 메타데이터 점검·COMMIT은 제외했습니다.
5. `pg_prepared_statements`에서 검색 SQL만 조회했습니다. 이 점검 SQL 자신은 필터로 제외했습니다.
6. 같은 SQL·backend·준비문 별칭의 이전 카운터와 비교해 `(Generic 증가, Custom 증가)`가 `(1,0)`이면 Generic, `(0,1)`이면 Custom으로 분류했습니다. 누적 스냅샷을 합산하지 않았습니다.
7. embedding ID·chunk ID·distance의 반환 순서 SHA-256을 대조했습니다. 최종 두 경로 전체의 해시 종류는 1개입니다.
8. 반복 측정 뒤 SQL별 `EXPLAIN (ANALYZE, BUFFERS, SUMMARY) EXECUTE`를 1회씩 별도 수집했습니다. 반복 표본에 합산하지 않았습니다.

```text
그림 2 — JDBC 준비문과 Generic Plan 선택을 구분하는 내부 흐름

Stage50.measure(): 같은 질문 D01, 수정 전후 교차 호출
  │
  ├─ pgJDBC prepareThreshold=5
  │    └─ 이름 있는 서버 준비문 사용 기준
  │         (실제 계획이 Generic이라는 뜻은 아님)
  │
  └─ Proxy transaction pooling → 이번 트랜잭션의 backend
       ├─ SET LOCAL 스키마·제한 → backend 별칭 확인
       ├─ executeQuery + 결과 수신 → 왕복 시간 측정
       ├─ backend 재확인 → 검색 준비문별 카운터 조회
       └─ COMMIT
              │
              ▼
집계: 같은 SQL·backend·준비문 직전 카운터와 비교
  ├─ Custom 증가 1 → Custom 표본
  ├─ Generic 증가 1 → Generic 표본
  └─ 그 외 → 미분류(이번 최종 120회에서는 0개)

서버 준비문의 Custom 5회 뒤 auto 비용 비교로 Generic 선택
클라이언트 6번째 호출에서 반드시 전환되는 구조가 아님
```

`isUseServerPrepare()`의 호출 후 플래그는 다음 실행 준비 상태입니다. 이를 실행된 계획의 증거로 사용하지 않았습니다.

## 관측 결과

### 반복 측정

p50은 중앙값, p95는 nearest-rank입니다. 단위는 ms이며, 계획별 표본 수를 따로 표시합니다.

| 경로·SQL | 전체 n / p50 | Custom n / p50 | Generic n / p50 | Generic p95 | 최초 Generic 클라이언트 호출 |
|---|---:|---:|---:|---:|---:|
| A 수정 전 | 30 / 9,695.66 | 14 / 1,022.62 | 16 / 9,755.10 | 9,903.19 | 12 |
| A 수정 후 | 30 / 1,009.72 | 14 / 1,023.68 | 16 / 977.76 | 1,332.48 | 11 |
| B 수정 전 | 30 / 9,760.66 | 14 / 1,044.87 | 16 / 9,880.39 | 10,023.98 | 11 |
| B 수정 후 | 30 / 1,036.02 | 14 / 1,058.54 | 16 / 1,016.81 | 1,281.01 | 13 |

- 최종 120회 모두 40행을 반환했고, 순서를 포함한 결과 해시가 일치했습니다. 미분류 0개입니다.
- Generic 표본의 p95는 각 16개 표본에서 나온 값입니다. 서비스 전체의 지연 분포나 SLO가 아닙니다.
- 수정 전은 Custom에서 Generic으로 바뀐 뒤 지연이 증가했고, 수정 후는 두 계획에서 비슷한 수준을 유지했습니다.

### 별도 DB 실행계획 관측

| 경로·SQL | DB Execution Time(ms), 각 n=1 | HNSW 사용 | InitPlan |
|---|---:|---|---|
| A 수정 전 | 9,523.687 | 없음 | 없음 |
| A 수정 후 | 773.386 | 없음 | 있음 |
| B 수정 전 | 9,579.769 | 없음 | 없음 |
| B 수정 후 | 749.155 | 없음 | 있음 |

두 SQL 모두 50,008개 행을 대상으로 top-N 정렬하는 계획이었습니다. HNSW 인덱스는 존재하지만 이 계획에서는 사용하지 않았습니다.
수정 전 정렬 키에는 `($3562)::vector`가 남아 있고, 수정 후에는 `(InitPlan 2).col1`을 참조했습니다.
수정 후 InitPlan 1·2는 각각 `loops=1`입니다. SQL에 두 변환식이 있으므로 전체에서 변환이 단 한 번이라고 표현하지 않습니다.

```text
그림 3 — 같은 데이터·인덱스에서 달라진 계산과 이번 관측

수정 전 SQL
  SELECT ... CAST(:queryVector AS vector)
  ORDER BY ... CAST(:queryVector AS vector)
       │ Generic Plan: 정렬 식에 매개변수→vector 변환이 남음
       └─ 많은 후보 행에서 반복 평가 → Generic p50 약 9.8초

수정 후 SQL(기존 #550)
  SELECT ... (SELECT CAST(:queryVector AS vector))
  ORDER BY ... (SELECT CAST(:queryVector AS vector))
       │ InitPlan 1·2가 각 한 번 평가하고 결과를 참조
       └─ 같은 Generic 조건에서 p50 약 1.0초

원본 로그·카운터·실행계획을 함께 대조
  ├─ 두 경로 모두 자연 Generic 선택
  ├─ 최종 120/120회 결과 순서·거리 해시 일치
  └─ 공개 HTTP 전체 지연이나 모든 쿼리의 개선으로 확장하지 않음
```

### 정리·보존

- 전용 DB 삭제 후 카탈로그 잔여 0개, 임시 Proxy 프로세스 0개·시험 포트 닫힘·시험 폴더 제거를 확인했습니다.
- 기존 Proxy 프로세스는 A/B 각각 1개 유지, 원본 Proxy·Patroni 설정 해시도 동일했습니다.
- 개인 환경 및 application 설정 7개 파일 해시·기존 HEAD·작업 상태 체크섬이 같았습니다. 당시 추적 변경 0개·기존 미추적 경로 28개였습니다.
- DB VM 3대 실행·비DB VM 4대 중지 상태를 유지했습니다. 기존 VM을 새로 기동하거나 공개 설정을 바꾸지 않았습니다.
- 관리자 인증은 명시 승인된 전용 DB 준비·확장 설치·정리에만 원격 메모리에서 사용했습니다. 인증값은 이 기록에 포함하지 않습니다.

## 검증 과정에서 확인된 사항

### 소규모 예비 관측만으로 중단할 수 없었던 이유

앞선 4,096청크 예비 관측은 문서 16개·최소 3테이블·합성 sin/cos 벡터였으며, 자연 Generic 선택이 관측되지 않았습니다.
그 결과만으로 기존 5만 청크 재현 조건을 대체하거나 실제 배포 경로에서 문제가 없다고 판단할 수 없었습니다.
이번에는 문서 수·스키마·벡터 분포도 함께 맞췄습니다. 청크 수 하나의 효과를 분리한 실험은 아닙니다.
예비 관측 원본은 로컬에 별도 보존했고, 이 문서의 최종 지연 집계에는 사용하지 않았습니다.

### 관측 도구 문제와 제외·재실행

첫 A R1은 검색 60회 수집 뒤 실행계획 마스킹·출력이 끝나지 않았습니다.
JVM main이 Throwable의 표준오류 출력에서 대기하는 상태를 확인했고, 같은 길이의 배열에 원래 정규식을 적용한 별도 로컬 진단에서는 StackOverflowError를 재현했습니다.
원래 회차의 최초 예외 헤더를 직접 수집하지 못했으므로, 별도 진단과 실제 프로세스 관측을 구분합니다.

반복 그룹이 있는 긴 배열 정규식을 문자 클래스로 바꾸고, 수집기가 표준오류를 동시에 소모하도록 보완했습니다.
이번 Java 자식 프로세스만 확인 후 종료하고 정리 경로를 실행했습니다. A R1 반환코드는 143이며 최종 비교에서 제외했습니다.
이후 같은 보완 계측기로 B R1과 A R2를 정상 종료·계획 수집까지 확인했습니다.
반복 검색 기록 **180개 중 유효 120개·제외 60개**입니다. 별도 EXPLAIN 실행과 혼동하지 않습니다.
준비 계측기는 4회 컴파일 모두 통과했으며, 이는 제품 빌드나 전체 회귀 시험을 수행했다는 뜻이 아닙니다.

### 해석 범위

- D01 하나·허용 100%·동시성 1입니다. 질문 30개 전체, 다른 권한 비율, 하이브리드·RAG·실제 모델을 이번에 실행하지 않았습니다.
- 원본 바이너리의 **별도 리스너**입니다. 변경하지 않은 공개 리스너나 Spring/Hikari 전체 경로의 전환 시점을 증명하지 않습니다.
- 물리 자원을 공유했고 배경 부하는 따로 계측하지 않았습니다. 절대 지연이나 다른 장비와의 속도 비교에 사용하지 않습니다.
- 카운터 조회도 Proxy를 통과합니다. 집계에서 점검문을 제외했지만 관측 부하 자체가 없었던 것은 아닙니다.
- 미추적 파일은 경로 목록·상태 체크섬을 대조했습니다. 기존 미추적 파일 내용 전체의 동일성을 해시로 검사한 것은 아닙니다.
- HNSW 크기 일치로 물리 그래프 동일성을 주장하지 않으며, 벡터 cache·생성 벡터 해시와 원본 출처를 별도로 기록했습니다.

## 근거와 재검산 방법

### 저장한 구성

| 파일·구성 | 역할 |
|---|---|
| [aggregate.json](aggregate.json) | 최종 두 회차의 지연·계획·결과 집계 |
| [Proxy A 요약](GP50-PROXY-A-RO-20261021-R2-summary.md) · [Proxy B 요약](GP50-PROXY-B-RO-20261021-R1-summary.md) | 실행별 구간·표본·설정 |
| 각 `*-events.jsonl` | 준비·반복 호출·실행계획·제외·정리의 기존 이벤트 원문 |
| 각 `*-classified.json` | 동일 준비문 카운터 증가량으로 분류한 호출별 근거 |
| [실행 색인](run-index.md) | 각 목적·반복 실행의 원본 파일 연결 |
| [출처·파일 해시](publication-manifest.json) | 공개한 원본 기록·실행 소스의 SHA-256. 로컬 원본과 동일한 바이트 복사 |
| [source/Stage50.java](source/Stage50.java) | 실행한 최종 GP50-R4 측정 소스. 서비스 코드가 아닌 증거 스냅샷 |
| [source/src/.../FullFixture.java](source/src/com/opensource/docgrid/domain/search/benchmark/FullFixture.java) | 기준 캐시·스키마·적재 순서 |
| [source/findTopK.sql](source/findTopK.sql) | 실제 사용한 수정 후 SQL |
| [verify_evidence.py](verify_evidence.py) | DB 접속 없이 원본 카운터·지연·결과·정리 및 원문 해시를 재검산 |

다음 명령은 **이미 수집한 기록을 다시 검사**합니다. 클라우드 실험을 새로 실행하는 명령이 아닙니다.

```bash
python3 docs/test-results/opensql-jdbc-generic-plan-20261021/verify_evidence.py
```

클라우드 재실행은 별도 전용 DB·승인된 관리자 준비·격리 리스너가 필요합니다.
원본 문서·캐시는 기존 #545 절차로 준비하고 위 fixture 출처와 대조한 뒤, 스냅샷 `FullFixture.seed()`→`Stage50.measure()`의 순서로 실행합니다.
SQL의 before 변환·바인딩·타이머·마스킹은 공개한 실행 소스에서 확인할 수 있습니다.
`GP_*` 입력은 승인된 시험 DB·캐시·SQL 경로를 전달하기 위한 계측 전용 값이며 제품의 새 환경변수가 아닙니다.
자동 관리자·SSH·프로젝트 탐색 도구, 개인 환경 파일, 문서/벡터 캐시, 컴파일된 `.class`는 공개하지 않았습니다.
따라서 이 폴더만으로 클라우드 시험이 자동 기동된다고 주장하지 않습니다.

기존 [harness-final.json](harness-final.json)의 `.class` 해시는 당시 컴파일 산출물의 기록이며 파일은 로컬에 보존했습니다.
공개 소스 무결성은 `publication-manifest.json`으로 검사합니다.

### 이번 기록 PR 준비 때 수행한 점검

- [근거 재검산](GP50-EVIDENCE-RECHECK-20261021-R1-events.jsonl): 기존 원문 31파일 해시, 유효 120회·결과 일치 120회·미분류 0회·제외 60회를 확인했습니다. DB에 접속하지 않았습니다.
- [공개 전 점검](GP50-PUBLICATION-AUDIT-20261021-R1-events.jsonl): 당시 선택 파일 36개·상대 링크 92개를 점검하고 알려진 민감값·개인 경로·이메일·비공개 IPv4 탐지 0건을 확인했습니다. 알려지지 않은 모든 비밀의 부재를 보장하는 검사는 아닙니다.

기존 클라우드 실행과 이번 문서 검산의 실행 ID·시각을 분리했습니다. 원본 실행 당시 수집한 로그를 이번 시각의 새 시험처럼 표현하지 않습니다.
