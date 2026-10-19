# 권한 회수 후 저장 답변·RAG 입력 재인가 수정 검증

관련 이슈: #566. [설계](../../design/issue-566-rag-current-authorization.md).

**결과: 수정 전 재현한 3개 경로가 모두 통과했고, 추가 경계를 포함한 실제 HTTP 보안 회귀 9/9개가 통과했다. 기본 백엔드 회귀는 1,525개 중 통과 1,520개·실패 0개·건너뜀 5개다.** [전체 집계](aggregate.json), [JUnit 집계](junit-counts.json), [최종 HTTP XML](http-final-junit.xml).

## 목적

문서 직접 접근은 403으로 차단되는데 과거 검색 기록이나 RAG 입력에는 회수 내용이 재등장하는 경계를 수정한다. 사용자 자신의 검색 소유권과 출처 문서의 현재 읽기 권한을 별개로 확인한다. 인용 링크만 숨기고 답변 본문이나 과거 문맥을 그대로 사용하는 것으로 해결됐다고 판단하지 않는다.

성공 기준은 회수 완료 뒤 새 조회·모델 입력 준비에서 회수된 합성 표식이 0건이고 계속 허용한 문서는 유지되는 것이다. 이미 사용자나 모델에 전송한 정보를 소급해 지우는 시험은 아니다.

## 테스트 환경

| 구성요소 | 실제 실행·대역의 경계 |
|---|---|
| 변경 전 소스 | `1272a016e3b83e3f028d45a3bf567dab6bee743f`. [변경 전 증거](before/README.md) |
| 변경 후 소스 | 위 커밋에서 분리한 `fix/566` 작업 트리. 실행 당시 커밋 전이므로 [전체 회귀 파일 해시](source-identity-7.json)와 [최종 HTTP 파일 해시](source-identity-8.json)를 보존. 운영 소스·리소스·build 파일이 두 실행에서 모두 일치 |
| DB·캐시 | 회차마다 새 로컬 PostgreSQL 17.8·pgvector 0.8.1 및 Valkey 9.1 컨테이너. 루프백 임의 포트, 합성 계정·비공개 문서 |
| 실제 사용자 경로 | Tomcat·BCrypt 로그인·JWT·SecurityFilter·권한 회수 HTTP·Primary 인가·문서 상세/본문/파일·검색 저장·답변/대화 조회 |
| 실제 RAG 경로 | RagFacade·PromptBuilder·claim·OllamaClient HTTP·답변/인용 영속화. claimNext/processJob 호출 시점은 시험에서 직접 제어 |
| 제어 대역 | 질의 임베딩·검색 후보·메모리 합성 파일·모델 응답 생성. 모델 서버는 실제 요청 본문을 수신·기록하는 루프백 제어 서버이며 실제 Ollama 추론은 아님 |
| 제외 | 공개 서비스·실제 사용자 문서·GCS·OpenSQL/OpenProxy HA·브라우저·백그라운드 스케줄러·완료 알림 |

모든 시각은 KST(+09:00)다. 로그인 응답·비밀번호·JWT·인증 헤더를 기록하지 않는다. 모델 입력·문서 응답은 합성 데이터만 사용하고 저장 전에 토큰·이메일 생략 처리를 한다. 기계별 경로·호스트명과 개인 작업 파일 목록은 공개 증거에서 제외했다. 공개 로그는 줄 끝 공백을 정규화했으며 실행 내용·판정은 변경하지 않았다.

## 테스트 방법

### 동일한 재현을 전후 비교

1. 문서 소유자와 조회 사용자를 별도로 만들고 USER 직접 읽기 권한을 부여한다. 대상 문서와 계속 허용할 문서는 모두 비공개다.
2. 실제 로그인 후 검색·답변을 생성한다. 고정된 검색 후보 대역은 권한 회수 후에도 대상 후보를 반환한다. 실제 SearchFacade가 현재 권한으로 제거해야 한다.
3. 소유자의 회수 API 204 뒤 권한 행 0개, 대상 상세/본문/파일 403, 허용 문서 200을 확인한다.
4. 기존 답변·대화 GET, 회수 전 접수된 작업의 첫 모델 입력, 후속 질문의 이전 답변 문맥을 확인한다.
5. 모델 처리 중 회수의 성공/오류 응답, NULL·손상 출처, 전이적 출처, timeout 대체 답변, 전체 근거 회수를 추가 시험한다.

```bash
./backend/gradlew -p backend permissionCurrentAuthorizationTest --no-daemon
./backend/gradlew -p backend test --no-daemon
```

로컬 실제 실행은 위 명령에 `--offline --console=plain`을 사용했다. 기본 회귀는 기존 CI와 같은 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`을 사용했다.

별도 HTTP 작업에는 `PERMISSION_VERIFY_JDBC_URL`, `PERMISSION_VERIFY_PASSWORD`, `PERMISSION_VERIFY_REDIS_PORT`, `PERMISSION_VERIFY_JWT_SECRET`, `PERMISSION_VERIFY_RUN_DIR`, `PERMISSION_VERIFY_RUN_SUFFIX`, `PERMISSION_VERIFY_SOURCE_REVISION`이 필요하다. 사용자는 기본 `verify_app`이며 `PERMISSION_VERIFY_USER`로 바꿀 수 있다. 새 전용 DB에 pgvector를 먼저 생성하고 새 전용 Valkey를 사용한다. 실제 개인·운영 DB를 지정하지 않는다. CI에는 기본 회귀와 분리된 전용 DB·Redis DB를 만드는 동일 검증 단계를 추가했다.

```text
그림 1 — 저장 답변·대화의 전후 차이 [실제 HTTP]

검색 → 발췌·답변·인용 저장 → 권한 회수 204 → 원문 접근 403
   │
   ├─ 수정 전 GET
   │    → 검색/대화 소유자 확인만 수행
   │    → 답변 3/3회·대화 3/3회에 회수 문구
   │
   └─ 수정 후 GET
        → 소유자 확인
        → 발췌·인용·답변의 전체 출처를 현재 Primary에서 재인가
        ├─ 허용 발췌: 순위를 다시 매겨 유지
        └─ 금지/불명 출처 답변: 전체 본문·인용 보류
             → 답변·대화 각각 회수 문구 0/3회
```

답변 문자열 일부가 어느 문서에서 왔는지 추정해 제거하지 않는다. 여러 문서가 섞인 답변은 한 출처라도 금지되면 전체를 안내문으로 대체한다. 저장된 `ragStatus`는 원래 생성 상태를 유지하며 안내문이 현재 표시 가능 여부를 설명한다.

## 관측 결과

### 동일 재현 세 경로

| 목적 | 수정 전 | 수정 후 최종 회차 | 판정·증거 |
|---|---|---|---|
| 기존 답변·대화 재조회 | 각각 3/3회 금지 문구 반환 | 각각 **0/3회**, 허용 문서 유지 | PASS. [AUTH-SAVED-FIX-R8](AUTH-SAVED-20261019-FIX-R8/events.jsonl) |
| 회수 전 접수 → 회수 후 모델 호출 | 첫 요청 1/1건에 금지 문구 | 첫 요청 **0/1건**, 허용 근거로 답변 생성 | PASS. [AUTH-QUEUED-FIX-R8](AUTH-QUEUED-20261019-FIX-R8/events.jsonl), [수신 입력](AUTH-QUEUED-20261019-FIX-R8/model-input-1.json) |
| 후속 질문의 이전 답변 문맥 | 새 결과는 허용 문서뿐이지만 후속 입력에 금지 문구 | 새 결과와 후속 입력 모두 금지 문구 없음 | PASS. [AUTH-HISTORY-FIX-R8](AUTH-HISTORY-20261019-FIX-R8/events.jsonl), [후속 입력](AUTH-HISTORY-20261019-FIX-R8/model-input-2.json) |

### 추가 보안 경계

| 목적 | 관측 | 판정·증거 |
|---|---|---|
| 모델 성공 응답 대기 중 회수 | 반환·저장 답변에 금지 문구 없음, FAILED 안내로 종료 | PASS. [SUCCESS](AUTH-INFLIGHT-20261019-SUCCESS-FIX-R8/events.jsonl) |
| 모델 오류 응답 대기 중 회수 | fallback에도 금지 문구 없음 | PASS. [FALLBACK](AUTH-INFLIGHT-20261019-FALLBACK-FIX-R8/events.jsonl) |
| 출처 불명/손상 | NULL·잘못된 JSON·음수 ID·문자열 ID 4조건에서 본문·인용 보류, 후속 입력 제외 | PASS. [LEGACY](AUTH-LEGACY-20261019-FIX-R8/events.jsonl) |
| 전이적 출처 | 현재 인용은 허용 문서만 있어도 과거 답변의 회수 출처를 추적해 표시·재사용 차단 | PASS. [TRANSITIVE](AUTH-TRANSITIVE-20261019-FIX-R8/events.jsonl) |
| timeout 대체 답변 | 예전 1위 대신 현재 허용 후보를 인용하고 실제 출처와 함께 확정 | PASS. [TIMEOUT](AUTH-TIMEOUT-20261019-FIX-R8/events.jsonl) |
| 모든 근거 회수 | 모델 요청 0회, 안내로 FAILED 종료, 금지 내용 없음 | PASS. [EMPTY](AUTH-EMPTY-20261019-FIX-R8/events.jsonl) |

모델 응답을 보류한 두 회차에서 같은 시험 DB의 다른 세션에 `idle in transaction`이 각각 **0개**였다. 이는 해당 제어 회차의 관측이며 운영 연결 점유량 개선율을 뜻하지 않는다.

### 전체 회귀·재시도

| 회차·목적 | 실제 결과 | 해석·원장 |
|---|---|---|
| 준비 1 | 시험 메서드 0개 | `pg_isready`가 DB 생성 전 임시 서버에 응답. 정확한 시험 DB 초기화 완료까지 기다리도록 실행기 보완. [분류](preparation-1.json) |
| HTTP 수정 R2 | 7/7 통과 | 초기 수정 증거. 이후 timeout/전체 회수/연결 관측을 추가. [로그](gradle-attempt-2.log) |
| 단위 R3 | 86개 중 6개 실패 | mock 설정 도중 다른 mock을 호출한 시험 코드 오류. [로그](gradle-attempt-3.log) |
| 컴파일 R4 | 메서드 0개 실행 | 시험 assertion 인자 수 오류. 오래된 JUnit을 잘못 집계한 기록기 한계도 별도 명시하고 이후 파일 시각으로 차단. [분류](run-4-classification.json), [로그](gradle-attempt-4.log) |
| 단위 R5 | 86개 중 1개 실패 | timeout 시험의 이전 Repository 호출 기대값을 새 계약으로 수정. [로그](gradle-attempt-5.log) |
| 기본 전체 R6 | 1,455개 중 실패 23개·건너뜀 5개 | DB 연결 한도 19개 초기화 실패와 연쇄 Context 중단 4개. 기존 CI의 풀 크기 3 적용 전 회차. [분류](run-6-classification.json), [로그](gradle-attempt-6.log) |
| 기본 전체 R7 | **1,525개: 통과 1,520·실패 0·건너뜀 5** | 최종 기본 회귀. [집계](junit-counts.json), [로그](gradle-attempt-7.log) |
| HTTP 최종 R8 | **9/9 통과·건너뜀 0** | 동일 재현 3개+추가 경계 6개. [사전 명세](plan-http-8.json), [로그](gradle-attempt-8.log), [XML](http-final-junit.xml) |

서로 다른 목적·반복 회차의 표본을 합산해 통과 수를 부풀리지 않는다. 최종 기본 회귀와 별도 HTTP 결과를 분리한다. 이전 실패를 후속 성공으로 덮어쓰지 않았다. 원시 JUnit 전체는 로컬에 별도 보존하고 공개 증거는 회차별 Gradle 로그·집계·최종 HTTP XML·응답·모델 입력으로 구성했다.

```text
그림 2 — 새 모델 입력과 전이적 출처 [구현·관측]

SearchFacade의 저장 결과
  → RagPromptPreparationService.prepare(queryId), 짧은 Primary 트랜잭션
      ├─ 현재 허용 후보만 선택 → 최대 3개
      └─ 과거 답변의 모든 출처가 허용될 때만 문맥 포함
  → 직접 후보 + 이전 답변 출처의 합집합을 JSON으로 기록
  → PROCESSING·token 일치·lease 유효 조건으로 프롬프트 갱신
  → DB 트랜잭션 종료 → 실제 OllamaClient HTTP 전송
      [제어 서버가 수신한 입력에 회수 문구 없음]
  → 답변 저장 전 현재 권한 재확인
      ├─ 허용: 보낸 후보와 동일한 인용을 답변과 함께 확정
      └─ 회수: 모델 원문 저장 없이 안내문으로 FAILED 확정
```

답변에 실제 인용되지 않은 이전 대화 내용도 모델 입력에 사용됐다면 출처에 포함한다. 이 전이적 출처가 없으면 후속 답변에서 다시 누출될 수 있다.

## 검증 과정에서 확인된 사항

### 자체 검토와 보완

[변경 diff 자체 검토](self-review.md)는 작성자의 검토 기록이며 독립 승인과 구분한다.

timeout 대체 답변이 과거 검색 1위 발췌를 사용하면 새 프롬프트 출처와 실제 반환 내용이 달라질 수 있었다. 현재 후보로 다시 준비하고 답변·출처를 한 조건부 UPDATE에서 함께 확정하도록 보완한 뒤 별도 실제 HTTP 회귀를 추가했다.

기존 대화 SQL 개수 시험은 **인가 대역을 둔 응답 일괄 조립 SQL**의 범위로 명시했다. 실제 재인가 비용까지 항상 5 SQL이라고 주장하지 않는다. 실제 권한 동작은 이번 HTTP 시험이 검증한다. 같은 요청 내 고유 문서는 중복 검사하지 않지만 출처 수가 커지면 인가 비용도 늘어난다.

### 이전 데이터·배포 영향

- V53은 nullable TEXT 컬럼을 추가한다. 기존 답변의 출처를 불확실한 인용 목록으로 역산해 허용하지 않는다.
- 출처가 NULL인 기존 답변은 본문·인용·후속 문맥이 보류된다. 사용자 질문과 현재 허용 발췌는 유지되며 새 검색으로 답변을 다시 만들 수 있다. 데이터 삭제는 하지 않는다.
- 새 API가 표시를 제한하더라도 이미 전달된 내용은 회수할 수 없다. 외부 HTTP 전송과 권한 회수 커밋을 원자적으로 묶지 않으며 새 읽기/입력 준비와 저장 전의 검사 시점을 구분한다.
- 공개 배포나 운영 마이그레이션은 이번 작업에서 실행하지 않았다. 입력 보호를 운영에 적용하려면 관련 백엔드 모두에 새 바이너리를 배포해야 한다.

### 정리·보존

회차마다 시험 소유 컨테이너 2개를 제거했다. 최종에도 기존 컨테이너 35개의 집합이 같았고 개인 `.env`, `frontend/.env.local`, 원래 체크아웃의 HEAD·작업 상태가 보존됐다. [최종 보존 결과](preservation-after-8.json). GCP 공개 설정에 접속하거나 변경하지 않았다.

```text
그림 3 — 검증과 공개 서비스의 분리

원래 사용자 체크아웃·개인 환경·기존 컨테이너 35개
   └─ 보존 확인만 수행

별도 fix/566 작업 트리
   → 새 PostgreSQL·Valkey·루프백 모델 HTTP 서버
   → 실제 로그인·HTTP·문서 권한·RAG 서비스
   → 회차별 합성 데이터·별도 원장
   → 결과 확정 → 소유 자원만 정리

이 결과가 증명하는 범위:
  같은 제품 코드의 로컬 권한 경계·소유권·회귀
이번에 증명하지 않은 범위:
  공개 배포 완료·실제 Ollama 품질·OpenSQL/OpenProxy 장애 동작
```

## 변경 구성요소

| 파일·구성요소 | 역할 |
|---|---|
| V53·RagResponse | 직접·전이적 출처 기록, 추적 불명 데이터의 안전한 구분 |
| RagDocumentAccessService·PrimaryDocumentPermissionQueryService | 엄격한 출처 해석과 현재 Primary 읽기 권한 판정 |
| SearchAnswerQueryService·SearchConversationQueryService | 발췌 필터, 답변·인용 전체 보류, 과거 문맥 재인가 |
| RagPromptPreparationService·RagFacade | 전송 전 재구성, 저장 전 재확인, timeout 포함 안전 fallback |
| RagResponseRepository·CommandService | 프롬프트 갱신의 token·lease fencing, 대체 답변·출처 동시 확정 |
| HTTP 회귀·단위 시험·CI | 같은 재현+추가 경계 검증, PR마다 별도 DB에서 자동 재시험 |

전체 원장의 파일 해시와 비식별 검사 결과는 [evidence-index](evidence-index.json)에 보존한다.
