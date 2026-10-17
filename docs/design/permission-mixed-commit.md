# 동기 권한 회수와 독립 쓰기의 on/local 비교

관련 이슈: #543. 실제 HTTP·인증·상세/본문/파일/검색 차단은 #542에서 별도로 검증한다. 이 문서는 혼합 부하의 설계이며 실행 결과는 `docs/test-results/permission-mixed-commit-20261017/`에 둔다.

## 질문과 고정 조건

권한 회수는 항상 `SET LOCAL synchronous_commit=on`이다. 같은 지정 동기·strict 클러스터에서 합성 독립 쓰기의 설정만 `on` 또는 `local`로 바꾼다. Patroni 모드·노드·스키마·애플리케이션·공유 Hikari 5개·요청 동시성 8개·각 종류 24건을 고정한다. 공개 클러스터 설정, 공개 기능 플래그, Worker DB 계정의 정책은 변경하지 않는다.

`local`은 Primary의 로컬 WAL flush를 기다리지만 원격 Standby ACK는 기다리지 않는다. `on`의 원격 확정은 지정 동기 Standby가 실제로 있고 strict가 켜진 조건에서 평가한다. `off`와 비교하지 않는다.

## 경계와 요청

```text
그림 1 — 격리 실행 경로

JUnit 수동 task · 동일 Spring 서비스 · 합성 사용자 Context
  ├─ 실제 PermissionController → 회수 Facade → SET LOCAL on
  │    └─ 권한 삭제 + 요청 원장 재주장 → guarded 물리 COMMIT
  └─ 시험 전용 독립 Controller → SET LOCAL on 또는 local
       └─ 별도 테이블·별도 UUID → guarded 물리 COMMIT
                      │
             같은 Hikari 최대 5개
                      │
     시험 전용 OpenProxy → 격리 OpenSQL Primary + Standby 2대
```

독립 쓰기는 권한 테이블과 FK·행 잠금을 공유하지 않는다. 두 요청 모두 같은 COMMIT 경고·5초 네트워크 deadline 판정을 쓴다. 독립 Controller는 테스트 소스의 `@TestComponent`이며 운영 엔드포인트가 아니다. 이 비교는 MockMvc로 실행하므로 실제 HTTP·JWT 결과로 부르지 않는다. 파일·모델·인덱싱 Worker 부하는 포함하지 않는다.

## 회차와 장애 제어

| 회차 | 독립 쓰기 | 제어 | 성공 판정 |
| --- | --- | --- | --- |
| on-ack-loss | on | 최초 4응답 뒤 Standby 2대의 격리 컨테이너 pause, 나머지 44요청 시작, 12초 뒤 unpause | SyncRep·풀 대기 측정, 결과 불명 같은 키 재확정 |
| local-ack-loss | local | 동일 제어 | local 응답이 풀 경합에도 진행하는지 관측 |
| local-primary-loss | local | 최초 4응답 뒤 두 Standby pause, 44요청 시작, 초기 2초, 보완 회차는 고정 10초 뒤 Primary 강제종료, unpause·자동승격 | 최초 204 키를 재시도 전에 최종 Primary와 대조 |
| on-primary-loss | on | 각 회차의 local과 동일한 복합 장애 | 회수·독립 on의 최초 성공 키 유실 0건 |

각 회차 전에 시험 데이터와 시험 DCS만 초기화하고 스키마 스냅샷을 복구한다. Primary는 같은 노드에서 시작하고 동기 후보·streaming 2대를 확인한다. 공유 호스트의 공개 컨테이너·설정은 대상이 아니다. Standby pause에 시험 Proxy가 함께 멈추지 않도록 별도 제한된 시험 Proxy 컨테이너를 사용한다.

```text
그림 2 — 성공 원장과 결과 불명의 분리

애플리케이션의 최초 응답 → DB 밖 requests.jsonl에 키·상태·시간 기록
             │
             ├─ 204 → 승격 후 같은 키가 실제 DB에 존재하는가?
             │         ├─ 회수/on 누락: 실패
             │         └─ 독립 local 누락: 위험의 관측값으로 기록, 재생성하지 않음
             │
             └─ 503/599 등 → 먼저 감사 종료 → 같은 키로만 최대 4회 재확정
                              └─ 새 WAL을 만드는 assertion 갱신 + COMMIT 재판정
```

같은 키의 단순 `DELETE 0행`을 확정 근거로 사용하지 않는다. 최초 성공의 유실을 재시도가 가리지 않도록 감사가 먼저다. 모든 회수와 최초 불명 키는 마지막에 확정되어야 한다. `SET LOCAL`이 풀에 남지 않는지도 검사한다.

## 계측·실패·한계

- 응답 원장: 실제 상태, UUID, 종류, 최초/재시도, 지연(ms), 독립 쓰기의 실제 current_setting.
- 100ms 간격 Hikari active/pending 원장. 기록 실패는 시험 실패로 처리한다.
- Primary SyncRep 대기 수, 장애 확인·해제·자동승격 시각을 별도 제어 원장에 실시간 기록한다.
- 실패 회차는 덮어쓰지 않는다. 자격 증명·개인 데이터·비공개 호스트/IP·프로젝트 ID는 기록하지 않는다.
- ACK 중단 중에는 공유 풀이 회수 on 대기로 가득 차 local도 대기할 수 있다. local이 항상 독립적으로 진행한다고 가정하지 않는다.
- 복합 Primary 상실은 유실 구간을 의도적으로 만든 시험이다. 일반 장애 확률·운영 RPO·SLA로 확장하지 않는다. 0건 유실 관측도 local의 내구성을 증명하지 않는다.
- 기존 원본 OpenProxy의 동기 역할 문제 때문에 격리 역할 어댑터를 사용한다. 공급사 제품 경로의 직접 지원 성공으로 부르지 않는다.
- 이전 HTTP 회차의 옛 Primary 재합류 지연은 미해결이다. 이번 시험은 자동승격 후 옛 Primary를 끈 채 원장을 대조하고, 다음 회차를 독립 초기화한다.
- 시험 종료 시 시험 Proxy·DB 컨테이너·시험 DCS·디렉터리·로컬 Valkey·새 시험 터널·런타임 비밀을 정리한다. 공개 VM은 정지하지 않는다.

## 실행 중 확인한 하네스 보완

- native DB의 Primary 승격과 OpenProxy의 실제 읽기 준비는 다른 시각이다. 승격 직후 Proxy가 `AllServersDown`을 반환한 실패 회차를 보존하고, 후속 회차는 실제 Primary 읽기 준비를 따로 확인한다.
- 감사는 연결 복구가 늦을 때 읽기만 최대 45초 재시도한다. 최초 성공 UUID를 조회하기 전에 업무 쓰기나 재확정을 하지 않는다. Primary가 아닌 연결은 허용하지 않는다.
- `PERMISSION_MIXED_SCENARIO`는 `all`(기본), `ack-loss`, `primary-loss`만 허용한다. 반복 목적에 맞춰 같은 두 정책의 쌍만 새 원장에 실행할 수 있다.
- 컨테이너 pause는 네트워크 차단이 아니다. 정지 중 커널에 전달된 WAL이 재개 뒤 처리될 수 있으므로 성공 유실이 0건이어도 local의 내구성을 보장한다고 결론내리지 않는다.
- ACK 쌍과 Primary 쌍은 각각 같은 하네스 안에서 비교하고, 하네스 리비전이 다른 두 목적을 같은 바이너리에서 실행했다고 표현하지 않는다.
