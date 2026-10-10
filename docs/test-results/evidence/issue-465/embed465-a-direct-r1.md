# embed465-a-direct-r1 — 앱 A에서 CPU 임베딩 직접 요청

| 항목 | 관측 |
| --- | --- |
| 목적 | 앱 A VM에서 CPU VM 내부 경로·모델 응답 확인 |
| 위치·시각 | GCP 앱 A VM, 2026-10-10 04:37:28–04:37:29 KST |
| 성공 기준 | readiness 200, 단일·배치 200, 1024차원 유한 벡터, 배치 2건 순서 보존 |
| 명령·방법 | Python `urllib.request`로 `/health/ready`, `/embed`, `/embed/batch`를 순차 HTTP 호출. 주소·벡터 본문은 출력 전 제거 |
| readiness | HTTP 200, 12.85ms |
| 단일 | HTTP 200, 1024차원, 모든 값 유한, 605.02ms |
| 배치 | HTTP 200, 2건, 차원·순서·유한성 충족, 437.65ms |
| 결과 | PASS. Java 앱 API가 아니라 VM에서 직접 모델 API를 호출한 결과 |
