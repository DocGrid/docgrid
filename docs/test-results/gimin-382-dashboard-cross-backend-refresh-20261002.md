# 두 백엔드 대시보드 갱신 신호 검증 (2026-10-02 KST)

- 관련 이슈: [#382](https://github.com/DocGrid/docgrid/issues/382)
- 시험 코드 기준: `fix/382`의 프로덕션 커밋 `d751836`, 테스트 커밋 `80488ac`
- 로컬 판정: Java 선택 회귀 24/24, Python 계측 5/5 통과.
- GCP 판정: **미실행**. A/B VM과 공용 Redis는 실행 중임을 읽기 전용으로 확인했으나, 앱 fixture는 종료돼 있고 시험 비밀 복사본도 삭제된 상태다. 보호된 자격증명을 안전하게 다시 공급받기 전에는 실제 A→B 수신을 주장하지 않는다.

## 원래 문제와 변경 경계

[이전 A/B 시험](gimin-380-gcp-ab-websocket-20261002.md)에서 A/B에 각 25개 WebSocket 구독이 있었고 A만 발행할 때 B 수신은 두 회차 모두 **0건**이었다. 각 JVM의 Spring SimpleBroker는 자기 연결만 알고, 공용 Redis가 역할 캐시를 공유해도 WebSocket 메시지를 다른 JVM으로 전송하지 않기 때문이다.

이번 변경은 대시보드 데이터·사용자 ID를 Redis로 보내지 않는다. A가 로컬 push 후 고유 인스턴스 식별자만 Pub/Sub 채널로 발행한다. B는 원격 신호를 debounce하고 **자기 DB에서 요약을 다시 계산**, 자기 STOMP 세션 후보를 primary에서 다시 인가한 뒤 자기 SimpleBroker로 보낸다. B의 원격 push는 다시 발행하지 않는다. 기능은 기본값 `false`이며 공용 Redis를 사용하는 A/B에서 `DASHBOARD_CROSS_NODE_ENABLED=true`로 켜야 한다.

```text
로컬 변경을 받은 A                         B의 구독자
  AFTER_COMMIT → localDirty                  │
  scheduler → getSummary() → primary 인가    │
  → A의 SimpleBroker → A 구독자               │
  → Redis 채널에 '갱신 필요'만 발행             │
                       └────────────────────▶ remoteDirty
                                             scheduler → getSummary()
                                             → primary 인가 → B의 SimpleBroker
                                             → B 구독자
                                             (Redis 재발행 없음)
```

Redis Pub/Sub 자체는 내구성 있는 큐가 아니다. B 구독이 끊겨 있던 동안의 신호는 저장되지 않는다. 재구독 callback에서 한 번 원격 dirty를 세워 최신 snapshot을 다시 보내지만, 발행 자체가 실패한 뒤 연결이 유지된 경우는 이 방식만으로 복구를 보장하지 않는다. Redis와 primary 장애 시의 가용성도 별도 주제다. `/user/queue`와 RAG 개인 알림은 이번 변경 범위가 아니다.

## 변경 파일

| 영역 | 파일 | 역할 |
| --- | --- | --- |
| 로컬/원격 구분 | `backend/src/main/java/com/opensource/docgrid/domain/dashboard/event/DashboardUpdateFlag.java` | 원인별 dirty 플래그를 독립적으로 소비한다. |
| 전송 스케줄 | `backend/src/main/java/com/opensource/docgrid/domain/dashboard/event/DashboardPushScheduler.java` | 같은 주기 안의 신호를 합치고 원격-only 전송은 재발행하지 않는다. |
| 인가/전송 | `backend/src/main/java/com/opensource/docgrid/domain/dashboard/controller/DashboardWebSocketController.java` | 기존 primary 기반 수신자별 인가를 로컬·원격 모두 유지한다. |
| Redis 신호 | `backend/src/main/java/com/opensource/docgrid/domain/dashboard/event/DashboardCrossNodeSignal.java` | 식별자만 발행하고 자기 echo를 무시하며 재구독 시 새로고침한다. |
| 설정 | `backend/src/main/java/com/opensource/docgrid/global/config/DashboardCrossNodeSignalConfig.java`, `backend/src/main/resources/application.yml`, `.env.example` | 설정을 명시적으로 켠 경우에만 Redis 리스너를 생성한다. |
| 계측 | `scripts/opensql/websocket_dashboard_revocation.py` | 실제 B 요약을 합성 발행 시각으로 오인하지 않고 수신 프레임 수만 세는 `--count-only`를 추가한다. |

## 실행 결과와 각 run의 정제 기록

원시 Gradle XML에는 실행 호스트명이 포함되므로 공개 증거로 복사하지 않았다. 아래 링크는 명령 결과에서 수치·예외 종류만 선택해 **기록 전에 비식별화한 한국어 실행 기록**이다. 실패한 준비 회차도 성공으로 덮어쓰지 않았다.

| run ID | 위치/목적 | 결과 | 해석·기록 |
| --- | --- | --- | --- |
| `fix382-local-unit-r1` | 로컬/단위 시험 준비 | Gradle 캐시 잠금 권한 오류, 시험 0건 | [기록](evidence/issue-382/fix382-local-unit-r1.md) |
| `fix382-local-unit-r2` | 로컬/단위 시험 | 19/19 통과 | [기록](evidence/issue-382/fix382-local-unit-r2.md) |
| `fix382-redis-r3` | 로컬/전용 Redis 통합 | 1/1 통과; 이후 두 독립 연결로 테스트를 강화 | [기록](evidence/issue-382/fix382-redis-r3.md) |
| `fix382-websocket-r4` | 로컬/기존 WebSocket 회귀 | PostgreSQL 연결 거절, 시험 4건 기동 실패 | [기록](evidence/issue-382/fix382-websocket-r4.md) |
| `fix382-websocket-r5` | 로컬/전용 DB 회귀 | pgvector 확장 미설치로 Flyway 기동 실패 | [기록](evidence/issue-382/fix382-websocket-r5.md) |
| `fix382-websocket-r6` | 로컬/확장 준비 후 재실행 | 4/4 통과 | [기록](evidence/issue-382/fix382-websocket-r6.md) |
| `fix382-regression-r7` | 로컬/최종 Java 선택 회귀 | 24/24 통과, skip 0 | [기록](evidence/issue-382/fix382-regression-r7.md) |
| `fix382-harness-r8` | 로컬/Python 계측 | 5/5 통과 | [기록](evidence/issue-382/fix382-harness-r8.md) |

로컬 통합 시험은 실제 Redis Pub/Sub에서 독립된 두 연결의 A→B·B→A 전달, 자기 echo 무시, B 구독 중단·재시작 뒤 snapshot 재계산 신호를 확인했다. 기존 WebSocket 통합 시험은 실제 PostgreSQL·pgvector에서 4건을 통과했다. **두 GCP JVM에서 실제 B 구독자가 수신했다는 증거는 아직 없다.**

## GCP 검증의 남은 조건

기존 시험용 백엔드 A/B·공용 Redis·부하 VM과 OpenSQL 3노드는 모두 `RUNNING`으로 조회됐다. 기존 A/B checkout에는 시험 파일의 미커밋 변경이 있어 보존한다. 자격증명이 안전하게 공급되면 `fix/382`를 별도 checkout에 배치하고 두 앱에서 `DASHBOARD_CROSS_NODE_ENABLED=true`를 설정한다. A만 합성 발행, B는 발행을 끈 상태에서 A/B 각각 25개 구독의 실제 STOMP 프레임을 `--observe-only --count-only`로 센다. B 수신이 0보다 크고, 두 서버의 인증 완료 세션과 primary 인가 경로가 확인돼야 클라우드 완료로 판정한다. Redis 단절·재구독과 역할 회수 중 전송은 별도 run으로 분리하며, 결과가 나오기 전에는 수치나 성공을 예측해 쓰지 않는다.

현재 자동 보안 검토가 원격 컨테이너 환경 변수 전체를 읽어 자격증명 위치를 찾는 명령을 거부했다. 이 경로를 우회하지 않는다. 보호된 파일 등 승인된 방식으로 필요한 시험 비밀을 공급받거나, 비밀 없이 가능한 로컬 검증까지만 완료된 것으로 표시한다.
