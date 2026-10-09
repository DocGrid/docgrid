# bge461-api-r1 — CPU 모델·단건·배치 계약

| 필드 | 값 |
| --- | --- |
| 시각 | 2026-10-10 04:19:15~04:20:00 KST |
| 목적 | 새 이미지의 모델 로드와 단건·4건 배치 계약 검증 |
| 위치 | GCP 시험 전용 CPU VM 내부, loopback 전용 시험 포트 |
| 방법 | 기존 컨테이너 중지 → 새 이미지 기동 → readiness·단건·4건 배치 호출 → 트랩으로 기존 컨테이너 재시작 |
| 성공 기준 | readiness·API HTTP 200, 단건 1024차원 유한값, 배치 4건·순서 [0,1,2,3]·모델 일치, 기존 컨테이너 원복 |
| readiness | 04:19:35 KST에 200; 7번째 폴링에서 준비 완료 |
| 단건 | HTTP 200, 16,461.51 ms, 1024차원, 유한값; 앱 조회 예산 5 s 초과 |
| 배치 | HTTP 200, 7,007.96 ms, 모델 일치, 4건, 순서 [0,1,2,3], 유한한 1024차원 |
| 정리 | 04:20:00 KST, 시험 컨테이너 제거·기존 컨테이너 `running`, 재시작 0회 |
| 판정 | 기능 PASS, 첫 단건 조회 지연 WARN. p95·동시성·실제 앱 경로 미측정 |

비식별 stdout 결과:

```json
{"readiness_http":200,"readiness_ms":9.77,"embedding_http":200,"embedding_ms":16461.51,"dimension":1024,"all_finite":true,"query_budget_5s_pass":false,"document_budget_30s_pass":true}
{"http":200,"elapsed_ms":7007.96,"model_matches":true,"item_count":4,"indices":[0,1,2,3],"vectors_valid":true,"document_budget_30s_pass":true}
```
