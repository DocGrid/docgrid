# 외부 보존 Job 재개 사전 점검 · PREFLIGHT-R1

## 실행 전 계획 (2026-10-17 03:30 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-PREFLIGHT-R1` |
| 목적 | 외부 보존 Job 재개 시험에 필요한 접근·클러스터·모델·격리 저장소를 확인하고, 안전하지 않으면 중단 |
| 위치·방법 | 로컬 GCP CLI에서 VM·OS Login 상태를 비식별 조회하고 만료되는 임시 키로 시험 VM에 읽기 전용 접속. DB·Barman·GCS 상태는 비식별 집계만 남김 |
| 합격 기준 | 리더 1·streaming Standby 2, Barman·외부 WAL 연속성, GCS 사본 감사 정상, 임시 키 접속·제거 가능, 실제 모델 또는 명시된 스텁과 격리 원본 경로 확인 |
| 시작 상태 | GCP 인증 1개와 VM RUNNING 8·TERMINATED 2 관측. 기본 OS Login SSH는 Permission denied. DB·GCS에는 쓰기 전 |

## 관측

2026-10-17 03:30~03:50 KST에 시험 환경을 읽기 전용으로 확인했다. 실행 중인 VM 8대, 중지 2대였고 DB는 리더 1대·복제본 2대다. 두 백엔드의 인덱싱 Worker와 동기화 Dispatcher는 모두 꺼져 있었다. DB에는 기존 PENDING Job 10건·PROCESSING Job 2건이 있어 새 시험 버전 ID로 Worker를 제한하기로 했다. 백업 VM의 외부 WAL 동기화·건강 점검과 GCS 감사 timer는 활성이다. Barman 완료 기준 백업 1건, 로컬 WAL 9개, 검사 22/22 항목 OK를 확인했다. 마지막 외부 WAL 전송과 GCS 감사도 오류 없이 완료된 기록이 있었다. 로컬 BGE-M3 `/health`와 `/embed` 실제 호출은 각각 HTTP 200이고 1,024차원 벡터를 받았다. 임시 SSH reverse-forward에서 원격 `/health` HTTP 200을 확인했다. 인증 전 앱 HTTP는 401로 응답해 터널 경로가 살아 있음을 확인했다. 원래 DB와 문서 원본은 변경하지 않았다. 사전 접속 구문 두 회차가 실패했으나, 읽기 전용 SQL 재시도에서 부서 3개와 최소 ID 1을 확인했다. 오류 회차를 성공으로 합산하지 않는다. 임시 OS Login 키는 시험 종료 시 제거한다. **사전 점검 통과.**
