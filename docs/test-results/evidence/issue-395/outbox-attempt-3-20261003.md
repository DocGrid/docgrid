# 실행 로그: Outbox 부분 실행과 태그 제외 확인

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-outbox-prep-03`, 2026-10-03 약 03:38 KST. 초 단위 시작 시각은 수집하지 못함 |
| 목적·위치 | vector·합성 JWT를 갖춘 localhost 임시 PostgreSQL에서 두 통합 suite 실행 시도 |
| 명령 | Gradle `test --tests ...EmbeddingJobLeaseRecoveryIntegrationTest --tests ...SyncTransactionBoundaryIntegrationTest` |
| 성공 기준 | 두 suite 모두 실행·통과, 숫자 집계와 임시 DB 정리 |
| 원본 관측 요약 | Gradle **BUILD SUCCESSFUL**, Outbox **5/5 통과**. lease suite는 기본 `test`의 `claim-concurrency` 태그 제외로 **0건 실행**. 뒤따른 결과 집계 스크립트가 없는 lease XML을 찾다 `StopIteration`으로 종료 코드 **1** |
| 해석 | Outbox 5건은 통과했지만 ‘두 suite 완료’는 아님. 전용 `claimConcurrencyTest`로 분리해야 함 |
| 변경·원복 | 임시 컨테이너 자동 삭제 확인. 최종 실행에서 두 목적을 별도 Gradle 태스크로 분리 |
