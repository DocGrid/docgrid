# 격리 Worker 재개·검색·새 업로드 · RESUME-R1

## 실행 전 계획 (2026-10-17 04:29 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-RESUME-R1` |
| 목적 | 복원된 PROCESSING Job을 실제 모델 Worker가 재획득해 INDEXED로 마치고 검색·새 업로드를 수행 |
| 위치·방법 | 격리 DB localhost 터널과 삭제 가능한 GCS 복원 버킷의 합성 원본을 연결한 로컬 최신 backend jar. Worker는 시험 버전 하나만 처리, BGE-M3 localhost 모델 사용 |
| 합격 기준 | Lease 회수 후 기존 Job 1건 INDEXED, Attempt 2건 이상, 대상 Chunk·ACTIVE Embedding 중복 0, 검색에 대상 문서 포함, 새 합성 업로드 201 |
| 보호 | 기존 DB의 다른 PENDING/PROCESSING Job은 필터로 제외. 30분 Standby 자동 원복 유지. 이 회차 후 즉시 앱·터널 중지와 원래 클러스터 원복 |
| 한계 | Ollama RAG 답변 품질은 이 시험의 합격 범위가 아님 |

## 관측

2026-10-17 04:30~04:33 KST에 격리 DB의 씨앗 `file_objects` **1행**을 별도 복원 버킷 원본으로 바꿨다. 원본은 합성 PDF **658바이트**, SHA-256이 보존본과 같았다. localhost 터널과 최신 백엔드 jar를 사용했으며 Worker는 시험 문서 버전 **1개**로 한정했다. 로컬 BGE-M3 `/health`는 HTTP **200**이었다.

| 점검 | 관측 | 판정 |
| --- | --- | --- |
| Worker 재개 | 동일 버전의 Job **1건**이 `PROCESSING` → `INDEXED`, Attempt **1 → 2건** | 통과 |
| 문서 정합성 | 버전 `INDEXED`, Chunk **1건**, ACTIVE Embedding **1건**, Chunk·Embedding 중복 **0건** | 통과 |
| API 검색 | 복원 계정 로그인 HTTP **200**, 문장 검색 HTTP **200**, 대상 문서 결과에 포함 | 통과 |
| 새 업로드 | 합성 TXT **83바이트** 업로드 HTTP **201**, 문서·버전·Job·FileObject 각 **1건**, 격리 GCS 객체 **83바이트** 확인 | 통과 |
| Spring 상태 | `/actuator/health/readiness` HTTP **200**. 집계 `/actuator/health`는 HTTP **503**이었으나 앱 API는 위처럼 동작 | 경고: 집계 health의 실패 원인 미분석 |

이 회차는 **검색 결과에 대상 문서가 포함됐는지**를 확인했다. 비동기 Ollama 답변의 완성·정확도, 원본 다운로드, 부하량과 지연 분포는 측정하지 않았다. GCS 객체 조회는 처음에 로컬 `gcloud storage`의 TLS 모듈 오류로 실패했고, 동일 객체를 `gsutil stat`으로 다시 확인했다. 최초 실패도 성공으로 합산하지 않는다.
