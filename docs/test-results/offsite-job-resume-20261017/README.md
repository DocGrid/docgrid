# 외부 기준 백업·WAL과 GCS 원본으로 중단 Job 재개 검증

2026-10-17 KST, GCP 시험 환경과 로컬 BGE-M3에서 실행했다. 기준 코드 리비전: `ed5d6019fd677bf1585249310bf5b6a721ff270a`. **외부 백업으로 되살린 격리 DB에서 중단된 인덱싱 Job이 완료되고, 복원 문서 검색과 새 업로드까지 확인했다.** 서비스 운영 DB를 복원본으로 대체한 시험은 아니다.

## 검증 경로와 결과

```text
기준 백업·WAL·timeline → 백업 VM 밖 비공개 보존본 → 격리 Barman 가져오기
                                   │
보존된 GCS 원본 ──→ 별도 복원 버킷    ├─→ 시험 Standby의 별도 PGDATA
                                   │      └─ PROCESSING Job + Attempt 1
                                   └─→ 로컬 backend + BGE-M3
                                          └─ INDEXED + Attempt 2 → 검색 200
                                                             └→ 새 업로드 201
```

| 단계 | 실행 증거 | 결과 |
| --- | --- | --- |
| 외부 백업·WAL | 기준 백업 가져오기·검증, timeline **20**, WAL/history **11개·117,443,528바이트**, SHA 불일치 **0** | 통과 |
| 복원 원본 | 합성 PDF **658바이트**와 보존본 SHA-256 일치, 별도 복원 버킷으로 복사 | 통과 |
| 격리 PITR | 원래 Standby 한 대를 잠시 중단하고 별도 DB를 localhost에서 실행. 버전 **1**, Job `PROCESSING`, Attempt **1** | 다섯 번째 기동 회차 통과 |
| Worker 재개 | 시험 버전만 처리. Job **1** `INDEXED`, Attempt **2**, Chunk **1**, ACTIVE Embedding **1**, 중복 **0** | 통과 |
| 사용자 API | 로그인 **200**, 검색 **200** 및 대상 문서 포함, 새 합성 TXT 업로드 **201**와 GCS **83바이트** | 통과 |
| 원복 | 리더 **1**·streaming Standby **2**, 지연 **0·0바이트**, Barman **3/3 OK**. 격리 GCS 객체 **2**·DB stage **4**·Barman stage **1** 정리 | 통과 |

## 실패·재시도와 주장 범위

- 격리 DB 기동 R1은 PGDATA 소유권 오류, R2는 SQL **20/20회 실패**(당시 원문 오류 미보존), R3은 부분 재생 stage 재사용에 따른 FATAL 복구 오류, R4는 목표 시각이 Attempt 커밋보다 앞서 Attempt **0건**이었다. 모두 즉시 원복하고 각 회차 뒤 리더 1·Standby 2와 Barman 3/3을 확인했다. R5에서 Job·Attempt를 함께 복원했다. [각 회차 기록](./pitr-runtime-r1.md), [R2](./pitr-runtime-r2.md), [R3](./pitr-runtime-r3.md), [R4](./pitr-runtime-r4.md), [R5](./pitr-runtime-r5.md).
- 로컬 앱의 `/actuator/health/readiness`는 **200**이었지만 집계 `/actuator/health`는 **503**이었다. 이 원인은 분석하지 않았다. 로그인·검색·업로드 HTTP 결과는 별도로 확인했다. `gcloud storage` 조회는 로컬 TLS 모듈 오류가 나 `gsutil stat`으로 재검증했다.
- 이는 **격리 복원과 단일 중단 Job 재개**의 기능 검증이다. 재해 복구의 RPO·RTO, 대량 동시 복원, Ollama 생성 답변, 원본 다운로드, 서비스 전체 전환 성공을 주장하지 않는다. 원래 DB의 합성 문서는 삭제 API **204**를 받았지만, 뒤이은 원래 DB SQL 조회가 실패해 잔여 Job 상태는 확인하지 못했다. 원본 보존 버킷의 **30일 보존본**은 정책상 남는다.

## 실행 기록

| 목적 | 기록 |
| --- | --- |
| 목표·보호 기준 | [plan.md](./plan.md) |
| 클러스터·백업 사전 확인 | [preflight-r1.md](./preflight-r1.md), [cutover-preflight-r1.md](./cutover-preflight-r1.md) |
| 합성 씨앗과 중단 | [seed-r1.md](./seed-r1.md) |
| 외부 백업 가져오기와 파일 복원 | [import-r1.md](./import-r1.md), [recover-files-r1.md](./recover-files-r1.md), [recover-files-r2.md](./recover-files-r2.md), [recover-files-r3.md](./recover-files-r3.md) |
| 격리 기동 회차 | [pitr-runtime-r1.md](./pitr-runtime-r1.md) ~ [pitr-runtime-r5.md](./pitr-runtime-r5.md) |
| Worker·API 검증 | [resume-r1.md](./resume-r1.md) |
| 원복·정리 | [cleanup-r1.md](./cleanup-r1.md) |

각 회차의 실행 전 목적·위치·방법·합격 기준은 해당 기록에 분리했다. 비공개 호스트·버킷·계정 식별자와 인증값은 수집 전에 제외했다.
