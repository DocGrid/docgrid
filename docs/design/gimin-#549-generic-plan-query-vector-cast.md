# Generic Plan에서 질문 Vector를 행마다 변환해 벡터 검색이 느려지는 문제 수정 (#549)

closes #549

## 1. 배경

#545(PR #547) 측정에서 같은 벡터 검색 SQL을 반복하면 6번째 실행부터 15~21배 느려지는 현상을 발견했다.

`VectorSearchRepository.findTopK`는 질문 Vector를 `'[v1,v2,...]'` 문자열로 받아 SQL 안에서 `CAST(:queryVector AS vector)`로 바꾼다.

- **Custom Plan:** PostgreSQL이 파라미터 값을 알고 계획을 세우면 이 CAST를 계획 단계에서 한 번 계산해 상수로 넣는다.
- **Generic Plan:** 같은 Prepared Statement가 반복되면 PostgreSQL은 값과 무관한 계획을 재사용한다. 이때 CAST는 `($1)::vector`라는 **식으로 남아 행마다 실행**된다. 1024차원 문자열 파싱이 검색 대상 청크 수만큼(정렬 키와 SELECT 거리 계산에서 각각) 반복된다.

운영에서도 일어나는 조건이다.

| 단계 | 기본값 | 효과 |
|---|---|---|
| JDBC 드라이버 | `prepareThreshold=5` | 같은 SQL을 5번 넘게 실행한 Connection은 Server Prepared Statement를 쓴다 |
| PostgreSQL | `plan_cache_mode=auto` | 그 Statement의 6번째 실행부터 Generic Plan을 고를 수 있다 |
| 검색 SQL | `IN (:permittedIds)` 목록 길이만 다르다 | 허용 문서 수가 같은 사용자의 검색은 SQL 문자열이 같아 Connection Pool에서 반복된다 |

#545 측정값(5만 청크, 허용 100%, topK 10)
- 서비스 호출 p50: Custom Plan 204.2ms, Generic Plan 4,226.3ms
- psql `auto` 모드: 같은 Statement 1~5회째 140.4~144.0ms → 6~8회째 2,099.0~2,114.9ms

## 2. 변경

질문 Vector 변환을 스칼라 서브쿼리로 감쌌다. 상관되지 않은 스칼라 서브쿼리는 PostgreSQL이 **InitPlan으로 실행마다 한 번만** 계산하고, 각 행은 그 결과를 참조한다.

```sql
-- findTopK
(e.vector <=> (SELECT CAST(:queryVector AS vector))) AS distance
...
ORDER BY e.vector <=> (SELECT CAST(:queryVector AS vector))

-- findLexicalCandidates (최종 SELECT의 거리 계산)
(e.vector <=> (SELECT CAST(:queryVector AS vector))) AS distance,
```

- `findLexicalCandidates`는 거리를 수십~수백 행에만 계산해 영향이 작다. 같은 규칙을 지키려고 함께 바꿨다.
- `countPatternMatches`는 질문 Vector를 쓰지 않는다.
- pgvector의 HNSW 정렬 Index Scan은 `ORDER BY 열 <=> (SELECT ...)` 형태도 쓸 수 있다. 회귀 테스트로 확인했다.
- 파라미터 타입, 서비스 코드, DB 스키마, 설정은 바꾸지 않았다.

### 고려한 다른 방법

| 방법 | 채택하지 않은 이유 |
|---|---|
| Vector 타입으로 Bind (pgvector-java 등) | 의존성과 Hibernate 타입 등록이 필요해 변경이 크다 |
| JDBC `prepareThreshold=0` 또는 DB 역할의 `plan_cache_mode=force_custom_plan` | 모든 쿼리의 계획 재사용을 끈다. 근본 원인(행마다 변환)은 남는다 |
| 검색 Transaction에서 `SET LOCAL plan_cache_mode` | DB 왕복이 늘고 서비스 코드가 바뀐다 |

## 3. API 계약과 오류

API 경로, 요청, 응답 필드, 오류 코드는 그대로다. 검색 결과(청크와 순서)도 같아야 한다.

| 상황 | 수정 전 | 수정 후 |
|---|---|---|
| Custom Plan으로 실행 | 질문 Vector 1회 변환 | 같음(InitPlan 1회) |
| Generic Plan으로 실행 | 질문 Vector를 행마다 변환 | InitPlan 1회 |
| 허용 문서가 없음 | 서비스가 DB를 조회하지 않고 빈 결과 | 같음 |
| 질문 Vector 문자열 형식 | 서비스가 `float[]`로 만든 `'[v1,...]'` 문자열만 전달 | 같음(외부 입력이 SQL에 직접 들어가지 않음) |

## 4. 검증

[측정 결과](../test-results/gimin-%23549-generic-plan-query-vector-cast.md)

- **회귀 통합 테스트** `VectorSearchRepositoryGenericPlanIntegrationTest` 3건
  1. Generic Plan 결과가 Custom Plan과 같은 청크·거리 순서인지
  2. Generic Plan 실행 계획의 정렬 키가 `$1`을 직접 쓰지 않고 InitPlan 결과를 쓰는지
  3. `enable_sort=off`일 때 HNSW 정렬 Index Scan을 여전히 쓰는지
- **재측정:** #545와 같은 환경(격리 컨테이너, 같은 이미지 digest·데이터·Seed·질문·하네스)에서 수정 전후를 비교했다.
