# 최종 점검과 무결성 확인 — run-final-check-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 모든 주입·복구 뒤 남은 Issue와 데이터 중복·누락을 한 번에 확인 |
| 시각 | 2026-10-16 16:06:06 KST (4단계 Backend 16:05:31 기동, 16:06:57 종료) |
| 환경 | 4단계 Backend(Worker·Dispatcher 켬), 버전 6개 |
| 방법 | `DRY_RUN` 전체 점검, [무결성 SQL](../sql/integrity-check.sql), 관리자 요약·Issue 목록 조회 |
| 결과 | OPEN Issue **1건**. 문서 C의 삭제 뒤 `MISSING_EMBEDDINGS`(기대와 다른 결과)만 남았다. 나머지 Issue 5건은 `RESOLVED` |

## Issue 최종 상태

| Issue | 유형 | 심각도 | 대상 버전 | 자동 복구 | 최종 상태 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `MISSING_EMBEDDINGS` | CRITICAL | 3 | 1회 | `RESOLVED` |
| 2 | `DELETED_DOCUMENT_RESIDUE` | CRITICAL | 4 | 대상 아님 | `RESOLVED` (Outbox 삭제 처리 뒤) |
| 3 | `MISSING_EMBEDDINGS` | CRITICAL | 4 | 대상 아님 | **`OPEN`** (삭제 문서, 기대와 다른 결과) |
| 4 | `MISSING_JOB` | ERROR | 5 | 1회 | `RESOLVED` |
| 5 | `STALLED_VERSION` | WARNING↔ERROR | 6 | 대상 아님 | `RESOLVED` (Worker 처리 뒤) |
| 6 | `MISSING_JOB` | ERROR | 6 | 1회 | `RESOLVED` |

관리자 요약 API는 `openCount=1`, `repairingCount=0`, `failedRepairCount=0`, `autoResolvedLast24hCount=3`이었다. 마지막 값은 복구 Event가 연결된 채 24시간 안에 `RESOLVED`가 된 Issue 수로, 이번에는 1·4·6번이다. 보고만 하는 Issue 2·5번은 이 수에 들어가지 않는다.

## 무결성

| 확인 | 결과 |
| --- | --- |
| 같은 Chunk·모델 Vector 중복 | 0행 |
| 버전당 살아 있는 Job 2개 이상 | 0행 |
| 미처리·실패 Outbox Event | 0행 |
| 복구 Event ↔ Job 연결 | 3건 모두 `PROCESSED`, 각각 Job 1개로 연결되어 `INDEXED` |
| 삭제되지 않은 시험 문서 A·B·D | Chunk 5 = ACTIVE Vector 5 |
| 삭제된 문서 C | ACTIVE 0, STALE 5 (물리 삭제 없음) |

수동 점검 요청은 모두 18회였고, 감사 Action도 18건이 남았다. 409로 거절된 단건 복구 요청은 Action을 남기지 않았다. 18회 모두 버전이 100개보다 적어 배치 1번에 `hasMore=false`로 끝났다.

## 증거

- [최종 점검 응답](final-dry-run-batch1.json), [Issue 목록](issues-all.json), [운영 요약](summary.json)
- [무결성 결과](integrity.txt), [전역 상태](global-final.txt)
- [전체 실행 이력](all-reconciliation-runs.txt), [전체 감사 Action](all-admin-actions.txt), [실행 조건](conditions.txt)
