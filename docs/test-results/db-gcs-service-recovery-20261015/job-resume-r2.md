# Job 처리 R2 — 시험 스텁 수정 전후

- **Run ID**: `DR-SERVICE-JOB-20261015-R2`
- **시각**: 2026-10-15 17:28~17:42 KST; 시도별 시각은 일부 미수집
- **실행 위치**: 로컬 격리 Worker·복원 OpenSQL·독립 GCS·고정 벡터 스텁
- **목적·방법**: 합성 문서의 PENDING Job 한 건에만 Worker 필터를 적용. 실패 시 격리 DB의 합성 시험 사용자에만 ADMIN 역할을 부여해 정식 `POST /admin/embedding-jobs/{id}/retry` 경로 사용
- **성공 기준**: 대상 Job `INDEXED`, 청크·임베딩 각 1건, 버전·Job 중복 0건

| 단계 | 관측 | 해석 |
| --- | --- | --- |
| 첫 Worker 시도와 수동 재시도 | Job `FAILED`, 1개 청크·임베딩 0개; 오류 유형 `INDEXING_STATE_INCONSISTENT` | 처음에는 원인 미확정 |
| 로컬 디버거·스텁 입력 검사 | `EmbeddingClient.validateBatchResponse()` 예외. 스텁에 도착한 `/embed/batch`는 `Transfer-Encoding: chunked`; 구버전 스텁은 Content-Length만 읽어 본문 0바이트·텍스트 0건으로 처리 | **시험 하네스 결함** 확인. 제품의 DB 상태 불일치로 단정하지 않음 |
| 스텁 수정 | chunked 본문을 읽도록 수정; `/embed/batch`에 `texts` 1건 확인 | 모델 서버의 전송 계약과 맞춤 |
| 최종 DB 대조 | 시도 총 6회: 실패 5·성공 1. Job·버전 `INDEXED`, 청크 1·임베딩 1, 대상 버전의 Job 1건 | 최종 판정 통과 |
| 후속 검색 | HTTP 200, 합성 문서가 결과에 포함 | 스텁 조건의 검색 경로 통과 |

실패 시도 각각의 완전한 원본 로그와 초 단위 시각은 저장하지 않았다. 요청 본문·토큰·민감 식별자는 기록하지 않았으며, 별도로 포착한 진단 이벤트 1건은 `failureType=INDEXING_STATE_INCONSISTENT`, `errorCode=DOCUMENT-EMBEDDING-001`이었다. **5회 실패 모두의 원인을 개별 실행 기록으로 증명한 것은 아니며**, chunked 파싱 오류와 수정 후 통과를 함께 관측해 하네스 원인으로 판단했다.

이 시험은 **PENDING Job을 백엔드 재시작 뒤 처리하고 FAILED Job을 관리 API로 재시도한 결과**다. Worker를 인덱싱 중 강제 종료하고 Lease 만료 후 다른 Worker가 재획득하는 시험은 수행하지 않았다.
