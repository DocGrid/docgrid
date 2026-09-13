# DocGrid Alertmanager

DocGrid의 Prometheus 경보를 묶고 중복을 억제한 뒤 조직의 알림 채널로 전달한다. 기본 설정은 외부
수신기를 호출하지 않는 `ui-only` receiver다. 채널을 선택하지 않아도 Alertmanager UI에서 firing,
silence, inhibition 상태를 확인할 수 있다.

## 실행과 확인

```bash
docker compose --profile monitoring up -d alertmanager prometheus
curl -f http://localhost:9093/-/ready
```

- Alertmanager UI: <http://localhost:9093>
- Prometheus Alertmanager discovery: <http://localhost:9090/api/v1/alertmanagers>
- Prometheus 경보: <http://localhost:9090/alerts>

포트가 겹치면 `.env`의 `ALERTMANAGER_PORT`를 바꾼다. 기본 포트는 loopback에만 bind되며 외부에
공개할 때는 reverse proxy 인증과 네트워크 접근 제어를 먼저 구성한다.

## Routing 정책

기본 설정은 `alertname`, `cluster`, `environment`, `severity`, `service`가 같은 경보를 한 알림으로
묶는다. 첫 warning은 30초 동안 모으고 같은 그룹의 변경은 5분 간격으로 보낸다. 해결되지 않은
warning은 4시간마다 반복한다. critical은 첫 대기 10초, 반복 1시간으로 더 빠르게 알린다.

Embedding Provider의 critical 경보가 firing이면 같은 cluster와 environment의 Provider warning과
`severity="warning"`, `dependency="embedding-provider"`인 파생 경보를 억제한다. 근본 원인이 해결되면 inhibition도
자동으로 해제된다.

## 수신 채널 연결

`examples/`의 파일은 각각 완전한 Alertmanager 설정이며 다음 채널을 지원한다.

| 파일 | Secret 파일 |
|---|---|
| `discord.yml` | `discord-webhook-url` |
| `slack.yml` | `slack-webhook-url` |
| `microsoft-teams.yml` | `microsoft-teams-webhook-url` |
| `telegram.yml` | `telegram-bot-token`, `telegram-chat-id` |
| `email.yml` | `smtp-password` |
| `webhook.yml` | `webhook-url` |

선택한 채널의 Secret을 `monitoring/alertmanager/secrets/`에 한 줄짜리 파일로 만들고 소유자만 읽게
한다. URL이나 token 끝에 불필요한 공백을 넣지 않는다.

```bash
printf '%s\n' '실제 Secret 값' > monitoring/alertmanager/secrets/slack-webhook-url
chmod 600 monitoring/alertmanager/secrets/slack-webhook-url
```

`.env`에서 예시 파일을 선택하고 컨테이너를 다시 만든다.

```dotenv
ALERTMANAGER_CONFIG_FILE=./monitoring/alertmanager/examples/slack.yml
```

```bash
docker compose --profile monitoring up -d --force-recreate alertmanager prometheus
```

Email 예시의 `to`, `from`, `smarthost`, `auth_username`과 Slack의 `channel`은 조직 값으로 수정한다.
Microsoft Teams는 폐기 예정인 기존 Connector 형식 대신 Workflows의 v2 webhook을 사용한다.
Alertmanager는 이 설정 파일에서 환경변수를 치환하지 않으므로 URL·token·SMTP password는 제공된
`*_file` 필드로 주입한다. `secrets/`의 실제 파일은 `.gitignore`가 제외한다.

## 설정 검증

전체 Prometheus 규칙, 기본·예시 Alertmanager 설정, Compose를 검사한다.

```bash
./monitoring/verify.sh
```

Prometheus가 테스트 경보를 Alertmanager로 전달하고 로컬 webhook이 grouped firing과 resolved를
받는지까지 확인한다. 이 테스트는 실제 채널이나 개발 DB를 사용하지 않는 격리 Compose stack이다.

```bash
./monitoring/verify.sh --e2e
```

E2E는 Provider critical과 파생 warning을 동시에 발생시켜 파생 경보가 webhook으로 전달되지 않는지도
확인한다. 임시 설정, 가짜 Secret, 수신 payload는 종료 시 삭제한다.

## 전달 장애 확인

1. Prometheus `/api/v1/alertmanagers`의 `activeAlertmanagers`가 한 개 이상인지 확인한다.
2. Alertmanager `/-/ready`와 UI가 정상인지 확인한다.
3. Alertmanager UI에서 경보가 silence 또는 inhibition 상태인지 확인한다.
4. 컨테이너 로그에서 receiver의 HTTP·SMTP 오류와 재시도 기록을 확인한다.
5. Secret 파일 경로, 권한, URL의 앞뒤 공백과 만료 여부를 확인한다.
6. 설정 변경 뒤 `./monitoring/verify.sh`를 통과시키고 컨테이너를 다시 만든다.

운영 경보 대응 순서는 [비동기 Pipeline 경보 Runbook](../../docs/runbooks/async-pipeline-alerts.md)을
따른다.
