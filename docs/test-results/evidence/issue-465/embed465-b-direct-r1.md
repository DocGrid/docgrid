# embed465-b-direct-r1 — 앱 B에서 CPU 임베딩 직접 요청

| 항목 | 관측 |
| --- | --- |
| 목적 | 앱 B VM에서 동일 CPU VM 내부 경로·모델 응답 확인 |
| 위치·시각 | GCP 앱 B VM, 2026-10-10 04:37:43 KST (시작·종료가 같은 초) |
| 성공 기준 | readiness 200, 단일·배치 200, 1024차원 유한 벡터, 배치 2건 순서 보존 |
| 명령·방법 | Python `urllib.request`로 `/health/ready`, `/embed`, `/embed/batch` 순차 HTTP 호출. 주소·벡터 본문은 출력 전 제거 |
| readiness | HTTP 200, 10.02ms |
| 단일 | HTTP 200, 1024차원, 모든 값 유한, 294.59ms |
| 배치 | HTTP 200, 2건, 차원·순서·유한성 충족, 310.40ms |
| 결과 | PASS. Java 앱 API 호출은 별도 E2E 범위 |
