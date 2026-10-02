# 두 백엔드 대시보드 갱신 신호 검증 (2026-10-02 KST)

- 관련 이슈: [#382](https://github.com/DocGrid/docgrid/issues/382)
- 시험 코드 기준: `fix/382`의 프로덕션 커밋 `d751836`, 기본 테스트 커밋 `80488ac`, 두 앱 STOMP 테스트 커밋 `3279ae9`
- 로컬 판정: Java 선택 회귀 **25/25**, Python 계측 5/5 통과. 두 독립 Spring 앱의 실제 A→B·B→A STOMP 수신도 포함한다.
- GCP 판정: **A→B·B→A 실제 STOMP 수신 확인**. A/B 각 25개 구독의 양방향 전달, 실제 ADMIN 회수 응답 전후 수신, 짧은 Redis 재시작 후 수신을 분리된 run으로 측정했다. 아래 수치는 이 시험 조건에서의 관측이며 내구성 있는 메시지 전달이나 모든 장애 경합의 보장을 뜻하지 않는다.

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
| 두 앱 통합 | `backend/src/test/java/com/opensource/docgrid/domain/dashboard/event/DashboardCrossNodeWebSocketIntegrationTest.java` | 두 Spring context·두 SimpleBroker·공용 DB/Redis를 띄우고 양방향 실제 STOMP 수신 및 독립 DB 재계산을 확인한다. |

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
| `fix382-ab-r9` | 로컬/두 Spring 앱 시험 초안 | Java `connectAsync` 오버로드 모호성으로 컴파일 실패, 실행 0건 | [기록](evidence/issue-382/fix382-ab-r9.md) |
| `fix382-ab-r10` | 로컬/컴파일 수정 후 | test와 local 프로필이 함께 활성화돼 JPA 스키마 검증 실패 | [기록](evidence/issue-382/fix382-ab-r10.md) |
| `fix382-ab-r11` | 로컬/명시적 test 프로필 | A→B STOMP 수신·독립 DB 요약 **1/1 통과** | [기록](evidence/issue-382/fix382-ab-r11.md) |
| `fix382-ab-r12` | 로컬/양방향 확장 | A→B와 B→A STOMP 수신·독립 DB 요약 **1/1 통과** | [기록](evidence/issue-382/fix382-ab-r12.md) |
| `fix382-regression-r13` | 로컬/양방향 시험 포함 첫 종합 회귀 | **25/25 통과**, 실패 0, skip 0 | [기록](evidence/issue-382/fix382-regression-r13.md) |
| `fix382-regression-r14` | 로컬/초기 수신이 없는 조용한 기준선 추가 후 재실행 | **25/25 통과**, 실패 0, skip 0 | [기록](evidence/issue-382/fix382-regression-r14.md) |
| `ab382-compile-r1` | GCP/A·B 격리 checkout 컴파일 | 두 VM `compileTestJava` 종료 코드 각각 0 | [기록](evidence/issue-382/gcp-compile-r1.md) |
| `ab382-harness-r1` | 로컬/회수 계측 분류 추가 | Python 단위 시험 **6/6 통과** | [기록](evidence/issue-382/gcp-harness-r1.md) |
| `ab382-a2b-r1` | GCP/첫 fixture 준비 | 출력 상위 디렉터리 누락으로 앱·샘플 **0건**; 새 run으로 재시도 | [기록](evidence/issue-382/gcp-a2b-r1.md) |
| `ab382-a2b-r2` | GCP/A만 발행 | A/B 각 25개 구독, A 1,700건·B **1,275건** 수신; B 합성 발행 0회 | [기록·원본](evidence/issue-382/gcp-a2b-r2/run.md) |
| `ab382-b2a` | GCP/B만 발행 | A/B 각 25개 구독, A **1,250건**·B 1,700건 수신; A 합성 발행 0회 | [기록·원본](evidence/issue-382/gcp-b2a/run.md) |
| `ab382-rev-a2b` | GCP/양쪽 구독 중 실제 ADMIN 회수 | HTTP 200 전 A 450건·B 300건; 응답 뒤 추가 수신 양쪽 **0건**, 연결 종료 50건 | [기록·원본](evidence/issue-382/gcp-revocation/run.md) |
| `ab382-redis-restart` | GCP/짧은 Redis 재시작 | 재시작 이후 A **550건**·B **400건** 추가 수신, Redis `active` | [기록·원본](evidence/issue-382/gcp-redis-restart/run.md) |

로컬 통합 시험은 실제 Redis Pub/Sub에서 독립된 두 연결의 A→B·B→A 전달, 자기 echo 무시, B 구독 중단·재시작 뒤 snapshot 재계산 신호를 확인했다. 한 단계 더 나아가 **서로 다른 Spring context 두 개와 각자의 SimpleBroker, 실제 PostgreSQL·Redis, STOMP 관리자 구독자**로 A와 B 양방향 수신을 확인했다. 반대편이 받은 것은 발행 측 합성 요약이 아니라 자신의 DB 재계산 값이었다. 기존 WebSocket 통합 시험 4건도 통과했다. GCP에서는 **별도 두 VM의 JVM**에서 같은 커밋 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880`을 컴파일·실행해 양방향 수신을 다시 확인했다.

## GCP 실행 환경·판정 범위

| 역할 | 실행 자원 | 실제 시험에서 한 일 |
| --- | --- | --- |
| 백엔드 A/B | GCP VM 2대, JVM 2개, 각 독립 SimpleBroker | 동일 앱 커밋, `DASHBOARD_CROSS_NODE_ENABLED=true`; 한쪽만 합성 발행하고 반대쪽은 미발행 |
| Redis | 별도 GCP VM 1대 | 역할 캐시와 갱신 신호용 공용 Redis; 한 run에서 서비스 재시작 |
| DB | OpenSQL 3노드 GCP VM 3대, OpenProxy 2개 | 앱의 실제 DB 연결. Flyway는 이번 fixture에서 비활성 |
| 부하 발생기 | 별도 GCP VM 1대 | 각 백엔드에 직접 25개씩 STOMP 연결, 프레임·HTTP 응답 시각 기록 |

총 **7대 VM**은 시험 후에도 요청에 따라 `RUNNING`으로 확인했고, Redis 서비스도 `active`였다. A/B의 기존 미커밋 checkout은 건드리지 않고 각각 별도 격리 checkout을 사용했다. DB 원본 자격 증명 파일과 Redis 원본 비밀번호 파일은 보존했다. 시험에만 사용한 A/B 임시 앱·Redis·JWT 파일, 부하 VM의 JWT·시험 사용자 ID 사본은 삭제됐고, 네 run 모두 시험 계정이 각 백엔드에서 2개씩 정리됐으며 fixture 종료 코드 0을 확인했다.

DB 최초 계정 발급 때 만든 root 전용 `0600` 파일에서 **앱 암호 키의 존재만** 출력 없이 확인한 후 암호화된 SSH 파이프로 필요한 값만 전달했다. 공개 로그에는 비밀값과 내부 호스트·IP·프로젝트 ID를 기록하지 않았고 사전·사후 패턴 검사를 통과했다. 원격 컨테이너 환경 변수 전체를 읽는 거부된 경로는 사용하지 않았다.

**한계:** 클라이언트 수신은 실제로 확인했지만 GCP 원본 로그에 모든 SQL의 도착 DB 역할을 개별 기록하지는 않았다. 권한 회수 run의 B 프레임은 실제 DB 요약이어서 발행 시각이 없다. 따라서 응답 후 수신 0건은 해당 관측 창의 결과이지 모든 타이밍에서 `200 이후 새 승인 0건`이라는 증명은 아니다. Redis 재시작도 초 단위로 끝난 짧은 장애여서 긴 단절 중 놓친 Pub/Sub 신호의 재동기화와 정확한 복구 시간은 아직 미측정이다. 이 세 가지는 후속 장애 시험에서 별도 판정해야 한다.
