# 합성 문서·중단 Job 생성 · SEED-R1

## 실행 전 계획 (2026-10-17 03:50 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-SEED-R1` |
| 목적 | 합성 PDF를 시험 백엔드에 올리고, 해당 버전 Job만 Worker가 claim한 뒤 중단해 복원 전 `PROCESSING` 상태를 만든다 |
| 위치·방법 | 로컬 IAP SSH 터널 → 시험 백엔드의 회원가입·로그인·Multipart 업로드. 시험 버전만 처리하는 임시 Worker와 로컬 BGE reverse-forward 사용 |
| 합격 기준 | 업로드 201, GCS 원본과 Job 1건, Attempt 1건 이상, Worker 중단 후 `PROCESSING`·Lease 기록. 외부 GCS 보존 객체와 목표 시점 WAL 확인 |
| 안전장치 | 고유 합성 문서·계정 사용, 임시 Worker는 버전 ID 필터, 기존 Job 12건은 처리하지 않음. 원본 보존본은 30일 최소 보존으로 남을 수 있음 |

## 관측

2026-10-17 03:50~04:05 KST에 합성 PDF **658바이트**를 업로드해 HTTP **201**, 새 버전·Job 각각 **1건**을 생성했다. 별도 Worker를 시험 버전 하나로 제한해 실행했다. 첫 unit은 환경 파일의 설정 우선순위 때문에 Job을 claim하지 못해 종료했고, 시험 프로필과 CLI 인자를 명시한 두 번째 unit에서 `PROCESSING`·Attempt **1건**·Lease를 확인했다. 로컬 합성 모델 요청 1건을 일부러 대기시킨 상태에서 그 임시 Worker만 SIGKILL로 종료했다. 종료 후에도 Job은 `PROCESSING`·Attempt **1건**이다. 원본 GCS와 별도 보존 객체 **658바이트·SHA-256 일치**, 해당 시각 이후 완료 WAL을 외부 버킷과 격리 stage에 동기화해 총 **11개**를 확인했다. **씨앗·보존 관문 통과**. 첫 unit의 미처리 상태와 두 번째 unit의 성공 관측은 구분한다.
