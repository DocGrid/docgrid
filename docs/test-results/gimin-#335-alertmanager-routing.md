# Alertmanager 경보 전달 검증 결과 (#335)

검증일: 2026-09-13

## 정적 설정 검증

```bash
./monitoring/verify.sh
```

결과: **SUCCESS**

- `promtool check config`: rule file 3개 로드
- `promtool check rules`: Backend 3개, Pipeline 9개, Embedding Provider 7개, 총 19개 규칙
- `promtool test rules`: Backend·Pipeline·Operational suite 모두 성공
- E2E firing·resolved fixture: 각 3개 규칙 파싱 성공
- `amtool check-config`: 기본, E2E, 채널 예시 6개 설정 모두 성공
- 기본 Compose와 monitoring profile Compose 렌더링 성공

예시 설정 검증에는 실행 중 삭제되는 임시 가짜 Secret 파일만 사용했다. 실제 webhook URL, token,
SMTP password는 사용하거나 저장하지 않았다.

## Prometheus → Alertmanager → Webhook E2E

```bash
./monitoring/alertmanager/tests/run-e2e.sh
```

결과: **SUCCESS**

| 검증 | 결과 |
|---|---|
| Prometheus Alertmanager discovery | active 대상 1개 |
| 같은 경보의 `backend-a`, `backend-b` instance | 하나의 webhook payload에 2개로 grouping |
| Embedding Provider critical + 파생 warning | 파생 경보 inhibition, webhook 전달 0건 |
| firing webhook | 전달 성공, discovery 확인 후 1초 |
| resolved webhook | 규칙 reload 후 3초 |
| 외부 수신 채널 | 호출하지 않음 |

E2E는 별도 Compose project와 임시 rule·payload 디렉터리를 만들고 종료 시 컨테이너, network, volume,
파일을 제거한다. 개발 DB와 실제 Alertmanager receiver를 사용하지 않는다.

## 번들 Alertmanager 실행 검증

```bash
docker compose --profile monitoring up -d --wait alertmanager
curl -f http://127.0.0.1:9093/-/ready
curl -f http://127.0.0.1:9093/api/v2/status
```

결과:

- `quay.io/prometheus/alertmanager:v0.33.1` 실행
- readiness `200 OK`
- HA cluster listener 비활성화 확인
- `ui-only` receiver와 inhibition rule 2개 로드
- 외부 receiver 설정 0개

## Secret 추적 검증

```bash
git ls-files monitoring/alertmanager/secrets
```

결과: `.gitkeep`만 추적한다. `.gitignore`는 해당 디렉터리의 나머지 파일을 제외한다.
