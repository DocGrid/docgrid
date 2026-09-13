# Alertmanager 경보 전달 설계 (#335)

closes #335

## 문제와 경계

Prometheus는 경보 상태를 계산하지만 채널별 전송, grouping, 반복, silence, inhibition을 담당하지 않는다.
Backend 안에서 Slack·Discord 전송을 구현하면 Backend가 중단된 상황을 알릴 수 없고 채널마다 재시도와
rate limit 코드를 유지해야 한다. DocGrid는 표준 Alertmanager를 외부 감시 경계로 사용한다.

## 기본 동작

번들 Alertmanager는 monitoring profile에서만 실행되고 `127.0.0.1:9093`에 bind된다. 기본 receiver는
외부 endpoint가 없는 `ui-only`여서 Secret 없이도 안전하게 실행할 수 있다. 사용자는 완전한 채널별
예시 설정 하나와 gitignored Secret 파일을 선택한다.

Prometheus는 내부 Compose DNS의 `alertmanager:9093`으로 경보를 전달한다. Backend와 Embedding
Provider target에는 `cluster`와 `environment` label을 두어 여러 배포의 알림이 섞이지 않게 한다.

## Routing과 억제

경보는 `alertname`, `cluster`, `environment`, `severity`, `service`로 묶는다. critical은 warning보다
짧게 기다리고 자주 반복한다. Embedding Provider의 critical 경보는 같은 배포의 Provider warning과
`dependency="embedding-provider"`인 파생 경보를 억제한다. 다른 cluster나 environment의 경보는
억제하지 않는다.

## Secret과 채널 확장

Webhook URL, bot token, SMTP password는 Alertmanager의 `*_file` 필드로 읽는다. 실제 파일은
`monitoring/alertmanager/secrets/`에 두고 Git에서 제외한다. Discord, Slack, Microsoft Teams v2,
Telegram, email, generic webhook은 Java 변경 없이 예시 설정 선택만으로 연결된다.

## 검증 경계

정적 검증은 고정 이미지의 `promtool`과 `amtool`로 모든 규칙·설정을 파싱한다. 동적 검증은 별도
Prometheus·Alertmanager·로컬 webhook receiver만 실행한다. 두 instance의 동일 경보가 한 payload로
묶이는지, Provider 파생 경보가 억제되는지, 규칙 해제 후 resolved payload가 도착하는지 검사한다.
