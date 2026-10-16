# 실제 삭제 경로 재시험 — live-deleted-residue-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 실제 HTTP 삭제와 Outbox 경로에서 수정 전 JAR는 오탐을 내고, 수정 JAR는 같은 DB의 기존 오탐을 닫고 다시 만들지 않는지 확인 |
| 시각 | 2026-10-16 16:25:30~16:27:40 KST |
| 환경 | 새 격리 PostgreSQL 17.8·pgvector 0.8.1, Valkey, BGE-M3 컨테이너. Backend 1대를 JAR와 Worker·Dispatcher 켜짐만 바꿔 네 번 기동. JAR 해시는 [jars.txt](jars.txt) |
| 입력 | #517과 같은 시험 문서 C([rc517-c-apiary.txt](../inputs/rc517-c-apiary.txt)), Chunk 5개 |
| 주입 | SQL 없음. Dispatcher를 끈 채 `DELETE /api/documents/3` |
| 성공 기준 | 수정 전 JAR: 삭제 처리 뒤 `MISSING_EMBEDDINGS` 재현. 수정 JAR: 삭제 직후 `DELETED_DOCUMENT_RESIDUE`만 탐지, 삭제 처리 뒤 탐지 0건, 기존 오탐 `RESOLVED`, OPEN 0건 |
| 결과 | 통과 |
| 정리 | Backend 종료, 컨테이너 3개 `docker rm -f -v`, 임시 저장소·비밀값·토큰 삭제 |

## 흐름

| 시각 (KST) | 단계 | JAR | Worker / Dispatcher | 관측 |
| --- | --- | --- | --- | --- |
| 16:25:30 | A0 기준선 `DRY_RUN` | 수정 | 켬 / 켬 | 버전 2개, 탐지 0건 |
| 16:25:31 | A1 업로드 | 수정 | 켬 / 켬 | 문서 C(`documentId=3`, `versionId=3`) |
| 16:25:58 | A2 사전 점검 | 수정 | 켬 / 켬 | `INDEXED`, Chunk 5·ACTIVE 5, 탐지 0건 |
| 16:26:29 | B1 삭제 | 수정 | 끔 / 끔 | HTTP 204, 문서 `DELETED`, ACTIVE 5, `DOCUMENT_DELETED` `PENDING` |
| 16:26:32 | B2 `DRY_RUN` | 수정 | 끔 / 끔 | Issue 1번 `DELETED_DOCUMENT_RESIDUE`만 탐지(1건) |
| 16:27:08 | 삭제 Event 처리 | **수정 전** | 끔 / 켬 | `PROCESSED`, Vector 5행 `STALE` |
| 16:27:09 | C1 `DRY_RUN` | **수정 전** | 끔 / 켬 | Issue 1번 `RESOLVED`. **Issue 2번 `MISSING_EMBEDDINGS` CRITICAL `OPEN`** — `{"activeEmbeddingCount":0,"currentModelEmbeddingCount":5,"allModelEmbeddingCount":5}`, #517과 같음 |
| 16:27:39 | D1 `DRY_RUN` | 수정 | 끔 / 켬 | 탐지 **0건**. Issue 2번 `RESOLVED`(재검사 정상 확인) |
| 16:27:39 | D2 `REPAIR` | 수정 | 끔 / 켬 | 탐지 0건, 복구 요청 0건 |
| 16:27:40 | D3 재점검 | 수정 | 끔 / 켬 | 탐지 0건. 요약 `openCount=0` |

최종 무결성 확인([결과](D-integrity.txt))에서 Vector 중복, 버전당 live Job 2개 이상, 미처리 Outbox는 모두 0행이었다.
삭제 문서는 ACTIVE 0·STALE 5로 남았고 물리 삭제는 없었다.

## 해석 범위

- 수정 JAR는 develop `f238caf`에 작업 트리의 `SyncConsistencyInspector` 수정을 더해 [전체 빌드](../full-build-01/)에서 만든 파일이다. 커밋 전 작업 트리에서 실행했다.
- 수정 전 JAR와 수정 JAR를 같은 DB에서 차례로 실행해, 수정 배포 전에 이미 열린 오탐이 배포 뒤 첫 점검에서 닫히는 경로도 함께 확인했다.
- 일부 Vector만 ACTIVE로 남은 삭제 문서는 실제 경로로 만들지 않았다. 삭제 Event는 문서의 ACTIVE Vector를 한 번에 STALE로 바꾸기 때문이다. 이 경우는 단위 시험으로만 확인했다.
- 문서 1건의 로컬 실행이다. 주기 점검과 A/B 다중 Backend는 시험하지 않았다.

## 증거

- API: [A0](A0-baseline-dry-run-batch1.json), [A1 업로드](A1-upload-response.json), [A2](A2-precheck-dry-run-batch1.json), [B1 삭제](B1-delete-response.txt), [B2](B2-detect-dry-run-batch1.json), [B2 Issue](B2-issues-all.json), [C1](C1-old-jar-dry-run-batch1.json), [C1 Issue](C1-issues-all.json), [D1](D1-fix-jar-dry-run-batch1.json), [D2](D2-fix-jar-repair-batch1.json), [D3](D3-fix-jar-recheck-batch1.json), [D3 Issue](D3-issues-all.json), [D3 요약](D3-summary.json)
- 상태: [인덱싱 뒤](state-A-indexed.txt), [삭제 직후](state-B-after-delete.txt), [삭제 Event 처리 뒤](state-C-after-dispatcher.txt), [전역 최종](global-final.txt), [무결성](D-integrity.txt)
- 로그: [단계별 Backend 핵심 줄](../logs/)
