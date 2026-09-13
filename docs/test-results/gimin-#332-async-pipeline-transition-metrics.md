# 비동기 Pipeline 상태 전이 메트릭 검증 결과 (#332)

검증일: 2026-09-13

## Java 검증

```bash
DB_PORT=55433 \
JWT_SECRET=docgrid-observability-test-secret-key-2026-with-at-least-32-bytes \
./gradlew test
```

결과: **1163 tests, 실패 0, errors 0, skipped 0**

새 검증은 다음 동작을 포함한다.

- Embedding retryable Provider 실패와 non-retryable 문서 오류의 label 분리
- Embedding 성공·재시도·최종 실패 Service의 metric event 발행
- RAG provider fallback과 timeout sweep Counter 분리
- RAG 예상 밖 실패의 실제 종료와 조건부 UPDATE 경합 패배 분기
- Sync Outbox 재시도와 최종 실패 Counter 분리
- Sync Outbox 실패·Lease 회수의 재예약과 최종 실패 event 분기
- Transaction commit 후 Counter 증가
- Transaction rollback 시 Counter 미생성
- RAG 조건부 UPDATE 경합 패배 시 metric event 미발행
- Sync Handler 실패 시 처리 성공 metric event 미발행

## Prometheus 설정과 규칙

```bash
docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  check config /etc/prometheus/prometheus.yml
```

결과: **성공**

- rule file 3개 로드
- Embedding Provider 규칙 7개
- Backend 규칙 3개
- 비동기 Pipeline 규칙 4개

```bash
docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules \
  /etc/prometheus/tests/docgrid-backend-alerts.test.yml \
  /etc/prometheus/tests/docgrid-pipeline-alerts.test.yml
```

결과: **두 rule test suite 모두 성공**

| 시나리오 | 결과 |
|---|---|
| retryable 실패 20%, 최소 표본 충족, `for: 5m` 충족 | 인덱싱 경보 발생 |
| `DOCUMENT_CONTENT_INVALID` 20% | 인덱싱 운영 경보 미발생 |
| RAG provider fallback 3회 | fallback 경보 발생 |
| RAG timeout sweep 3회 | timeout 경보 발생 |
| RAG success만 발생 | RAG 경보 미발생 |
| Outbox `retry_scheduled`만 발생 | 최종 실패 경보 미발생 |
| Outbox `terminal_failure` 발생 | 최종 실패 경보 발생 |

## Compose 검증

```bash
docker compose config
docker compose --profile monitoring config
```

결과: **두 구성 모두 정상 렌더링**

## 테스트 환경 참고

격리 checkout에는 Git 제외 `.env`가 없으므로 전체 테스트에 테스트용 `JWT_SECRET`을 명시했다.
기존 PostgreSQL 컨테이너가 Host `55433`에 연결되어 있어 `DB_PORT=55433`을 사용했다. 실제 Secret은
출력하거나 저장하지 않았으며 테스트 전용 값만 사용했다.
