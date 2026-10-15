# Embedding Job Attempt 최초·재전송 응답의 시각 정밀도 일치 (#511)

closes #511

## 1. 배경

같은 Job·Worker·Claim Token의 Attempt 시작 요청은 기존 Attempt를 반환한다.
최초 응답은 생성한 엔티티의 `startedAt`을 사용하고, 재전송 응답은 PostgreSQL에서 읽은 값을 사용한다.
마이크로초보다 정밀한 Clock 값이 들어오면 PostgreSQL `timestamp` 저장 전후의 정밀도가 달라져 두 응답 본문이 어긋날 수 있다.

이 수정은 CI 추가 PR #510에서 처음 적용한 변경을 별도 Fix PR로 분리한 것이다.
기존 구현 이슈·PR은 #63과 #64다.

## 2. 변경

`EmbeddingJobAttemptService.start()`에서 Job 행 잠금을 획득한 뒤 구한 현재 시각을
`truncatedTo(ChronoUnit.MICROS)`로 정규화한다. 이 값을 기존 소유권 검증과 Attempt 생성에 전달한다.

- DB 저장 전에 응답에 사용할 시각의 정밀도를 맞춘다.
- 같은 Claim의 기존 Attempt 조회·재전송 분기와 Job별 Attempt 번호 할당은 유지한다.
- DB 스키마와 CI Workflow, 테스트 코드는 이 Fix PR에서 변경하지 않는다.

## 3. API 계약과 오류

- 입력: 기존 Job 식별자, Worker 식별자와 Claim Token.
- 최초 생성: 기존 응답 본문과 HTTP 201을 반환한다.
- 같은 현재 Claim 재전송: 기존 Attempt 응답 본문과 HTTP 200을 반환한다.
- HTTP 상태와 별도로, 같은 Attempt를 반환하는 두 응답 본문의 시각은 일치해야 한다.
- 기존 Job 미존재·소유권 검증·Worker 불일치 오류와 응답 스키마는 변경하지 않는다.

## 4. 기존 관측 기록과 이번 분리의 범위

Linux CI의 기존 `EmbeddingJobAttemptIntegrationTest`에서 최초 응답의
`2026-10-15T17:06:20.436367321`과 재전송 응답의
`2026-10-15T17:06:20.436367`이 달라 응답 동일성 단언이 실패한 기록이 있다.

- 기존 실패 실행: https://github.com/DocGrid/docgrid/actions/runs/38773938963
- 기존 수정 내용: https://github.com/DocGrid/docgrid/commit/b40730a
- 상세 CI 이력: [#510](https://github.com/DocGrid/docgrid/pull/510)

위 링크는 분리 이전 실행의 기록이다. 이번 분리 커밋에서 새 테스트를 실행한 결과로 취급하지 않는다.
운영 환경에서 같은 문제가 발생했는지와 OS별 Clock 정밀도 차이는 이번 작업에서 검증하지 않았다.

## 5. 후속 검증 계획 (미실행)

1. 마이크로초보다 정밀한 값을 반환하는 고정 Clock을 사용한다.
2. 같은 시각 기준으로 유효한 Worker·Claim·Lease를 준비한다.
3. 실제 PostgreSQL에서 첫 요청을 커밋한 뒤 별도 Transaction의 같은 요청으로 재조회한다.
4. 최초·재전송 응답 본문 전체가 같고 Attempt가 1건인지 확인한다.
5. 동일 시험이 수정 전 코드에서 실패하고 수정 후 통과하는지 각각 기록한다.

후속 시험 코드는 이번 PR 분리 요청에 포함하지 않는다.
