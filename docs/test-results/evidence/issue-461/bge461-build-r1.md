# bge461-build-r1 — 기본 CPU 이미지 빌드

| 필드 | 값 |
| --- | --- |
| 시각 | 2026-10-10 04:17:38 KST 이미지 생성 완료; 빌드 시작 초 단위 시각은 미보존 |
| 목적 | 임시 Dockerfile 덮어쓰기 없이 의존성 설치·import 검증 |
| 위치 | GCP 시험 전용 CPU VM 1 vCPU·6 GiB |
| 기준 | `fix/461`의 `backend/embedding-server/Dockerfile`, `requirements.txt` |
| 절차 | 두 파일 SHA-256 일치 확인 → `docker build --quiet --tag docgrid-embedding:cpu-fix461 bge461-source` |
| 성공 기준 | 종료 코드 0, 이미지 생성, 빌드 중 `from FlagEmbedding import BGEM3FlagModel` 성공 |
| 결과 | 종료 코드 0; 이미지 SHA-256 생성; 전송 파일 2개 SHA-256 일치 |
| 판정 | PASS. 모델 다운로드·HTTP 응답은 별도 API 실행에서 확인 |
| 비식별 | 이미지 식별자와 VM·프로젝트·내부 주소는 이 로그에 기록하지 않음 |
