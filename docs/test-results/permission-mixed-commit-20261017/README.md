# 동기 권한 회수와 독립 쓰기의 on/local 비교

## 결과와 검증 범위

**ACK 중단 2조건과 복합 Primary 상실 2조건이 통과했다.** 대표 비교는 총 최초 요청 192건(조건별 회수 24건+독립 24건)이다. [전체 집계](aggregate.json), [ACK 회차 집계](aggregate-mixed-run02.json), [Primary 회차 집계](aggregate-mixed-run05.json), [설계](../../design/permission-mixed-commit.md).

회수는 항상 `on`이다. 별도 합성 독립 쓰기의 설정만 `on/local`로 바꿨다. 동일한 격리 OpenSQL·지정 동기 strict·업무 코드·공유 Hikari 5개·동시 요청 8개를 사용했다. 공개 설정, 공개 기능 플래그, 운영 Worker 계정의 정책을 변경하지 않았다.

이 비교는 실제 서비스·JPA·DB를 실행하는 **MockMvc 요청**이다. 실제 HTTP·로그인·JWT·Valkey·새 응답 차단은 [별도 HTTP 시험](../permission-http-failover-20261017/README.md)과 PR #544의 결과다.

| 조건·원장 | 최초 회수 204 | 최초 독립 204 | 최초 불명 회수/독립 | 최초 성공 누락 회수/독립 | Hikari 최대 active/pending | 독립 응답 p95(ms) |
| --- | --- | --- | --- | --- | --- | --- |
| [on-ack-loss](mixed-run02/on-ack-loss/requests.jsonl) | 17/24 | 15/24 | 7 / 9 | 0 / 0 | 5 / 8 | 9344.1 |
| [local-ack-loss](mixed-run02/local-ack-loss/requests.jsonl) | 15/24 | 21/24 | 9 / 3 | 0 / 0 | 5 / 8 | 5006.9 |
| [local-primary-loss](mixed-run05/local-primary-loss/requests.jsonl) | 2/24 | 5/24 | 22 / 19 | 0 / 0 | 5 / 8 | 5008.0 |
| [on-primary-loss](mixed-run05/on-primary-loss/requests.jsonl) | 2/24 | 2/24 | 22 / 22 | 0 / 0 | 5 / 8 | 6898.7 |

ACK 두 정책은 mixed-run02의 같은 하네스에서, Primary 두 정책은 mixed-run05의 같은 하네스에서 비교했다. 두 목적 사이에 **Proxy 준비 확인·감사 읽기 재시도**를 보완했으며 업무 코드는 동일하다. 두 하네스의 소스 hash와 회차를 합쳐 같은 바이너리였다고 표현하지 않는다.

지연은 MVC 호출 시작→반환이며 executor 대기와 사전 준비는 제외한다. 종류마다 표본 24건·조건별 1회이고 p95는 운영 성능·SLA가 아니다. active/pending은 서로 다른 시점의 독립 최대값으로 합산한 동시 요청 수가 아니다.

## 실행 경로와 실제 차이

```text
그림 1 — 같은 풀에서 커밋 설정 하나를 바꾼 경로

8개 요청 작업자
  ├─ PermissionController → DocumentPermissionRevocationFacade.revoke
  │    └─ SET LOCAL on → 권한·캐시·원장 assertion 변경 → guarded COMMIT
  └─ 시험 전용 IndependentWriteController
       └─ SET LOCAL on/local → 별도 UUID·별도 테이블 → guarded COMMIT
                       │
                공유 Hikari 최대 5개
                       │
    별도 시험 Proxy → 역할 어댑터 → 격리 Primary + Standby 2대
```

독립 테이블은 권한과 FK·행 잠금을 공유하지 않는다. 독립 API는 테스트 소스에만 있고 운영 엔드포인트가 아니다. 독립 204에는 실제 연결의 `current_setting`을 응답 헤더로 확인한다. `requests.jsonl.configured_mode`는 **회차의 독립 쓰기 정책**이다. 회수 행에도 회차 값이 기록되지만 실제 회수 트랜잭션은 Facade에서 항상 on이다. `observed_mode`는 독립 204에서 실측한 값이다.

- ACK 중단 `on`: 실제 중단 13.048초 안에 독립 204 **0건**, 회수 204 0건.
- ACK 중단 `local`: 실제 중단 12.917초 안에 독립 204 **5건**, 회수 204 0건.

local은 이번 표본에서 일부 독립 쓰기를 진행시켰지만 공유 풀 대기는 남았다. 실제 인덱싱 Worker·Chunk/Embedding 부하나 전용 풀 분리 효과는 측정하지 않았다. 즉시 운영 local 도입을 결정한 결과가 아니다.

## 최초 성공과 결과 불명을 분리한 감사

```text
그림 2 — 재시도로 성공 유실을 숨기지 않는 순서

최초 응답 → DB 밖 requests.jsonl에 UUID·상태·시간을 즉시 기록
  │
  ├─ 204 → 승격 뒤 같은 UUID가 현재 Primary에 있는가?
  │         ├─ 회수/on 누락: 시험 실패
  │         └─ 독립 local 누락: 실제 관측값으로 기록, 재생성하지 않음
  │
  └─ 503/599 등 → 최초 성공 감사가 끝난 뒤 같은 UUID로 재확정
                  → assertion UPDATE로 새 WAL 생성 → COMMIT 재판정
                  → 최종 키 존재·회수 권한 0행 확인
```

최초 불명 키는 모두 실제 204 재확정과 최종 키 존재를 요구한다. 회수 최종 원장은 각 24개이고 대상 권한은 0행이다. 매 회차 마지막 풀의 기본 커밋 설정도 `on`이다. 감사 읽기 재시도는 업무 쓰기를 수행하지 않으며 최초 성공 키 대조 순서를 바꾸지 않는다.

## Primary 상실과 준비 상태의 차이

매 회차 시험 데이터·시험 DCS만 초기화하고 같은 노드에서 Primary를 시작한다. streaming 2대·strict·실제 동기 후보·Proxy Primary 도착을 확인한다. 첫 4개 응답(회수2+독립2)은 모두 204인 것을 확인한 뒤 두 시험 Standby 컨테이너를 pause하고 나머지 44요청을 시작한다.

Primary 회차는 **복제 처리 중단→고정 10초 대기→Primary KILL→Standby unpause→native 자동승격**의 복합 장애다. 단순 Primary 상실 시험이나 원격 ACK 대기만 고립시킨 시험으로 부르지 않는다. 실제 제어 시간에는 SSH 지연이 포함된다. 시험 Proxy는 별도 컨테이너여서 Standby와 함께 정지하지 않지만, Standby의 SQL·역할 API 지연도 영향을 줄 수 있다.

```text
그림 3 — DB 승격과 실제 접속 준비를 따로 관측

Primary KILL 확인
  → native Patroni 새 Primary·running 관측
     → 시험 Proxy의 실제 read-write 트랜잭션에서 Primary 읽기 확인
        ├─ 일시 AllServersDown: 읽기 준비 재확인, 원시 주소는 기록하지 않음
        └─ 성공: fault.done → 최초 키 감사
                  └─ Hikari 복구가 늦으면 감사 읽기만 최대 45초 재시도
                     → 감사가 끝난 뒤에만 불명 요청을 재확정

옛 Primary는 꺼진 상태를 유지해 새 Primary에서 검증한다.
```

- `local`: pause→종료 확인 11.097초, 종료 확인→DB 승격 19.372초, →Proxy 읽기 준비 25.158초. 중단창 독립 204 3건, 최초 성공 독립 키 누락 0건.
- `on`: pause→종료 확인 11.029초, 종료 확인→DB 승격 19.191초, →Proxy 읽기 준비 20.963초. 중단창 독립 204 0건, 최초 성공 독립 키 누락 0건.

**이번 대표 비교에서 local 성공 유실은 관측되지 않았다.**

컨테이너 pause는 네트워크 전송과 커널 소켓 버퍼를 차단하지 않는다. 정지 중 전달된 WAL이 재개 뒤 처리될 수 있으나, 이번에 패킷·버퍼를 계측해 보존 원인을 확정한 것은 아니다. 수신 버퍼 폐기나 네트워크 단절은 실행하지 않았다. local의 내구성·RPO 0이나 전체 서비스 RTO를 주장하지 않는다. 옛 Primary의 자동 재합류도 검증하지 않았다.

## 실패·보완 회차와 원장

| 회차 | 실제 결과·판정 |
| --- | --- |
| [mixed-run01](mixed-run01/failure-notes.json) | ACK 첫 감사 뒤 전용 SSH 채널3개가 종료돼 재확정·최종 감사 실패. 비교 성공에서 제외. 첫 SyncRep 조회의 논리 DB 이름 필터도 잘못되어 무효로 기록 |
| [mixed-run02](aggregate-mixed-run02.json) | 네 조건 통과. 대표 ACK 쌍으로 사용. Primary는 2초 대기 설정의 중단창에 local204가 0건이어서 유실 위험 대조를 보완하기로 결정 |
| [mixed-run03](mixed-run03/failure-notes.json) | 고정10초 창에서 local204 3건이 나온 뒤 DB 승격. Proxy AllServersDown으로 최초 감사 실패. **실패 유지** |
| [run03 후속 읽기 감사](mixed-run03/post-failure-audit.log) | 재확정 쓰기 없이 회수 성공2/2·local 성공5/5 존재를 확인. 실패 회차를 통과로 바꾸지 않음 |
| [mixed-run04](mixed-run04/failure-notes.json) | 준비 점검 중 전용 채널 종료. 업무 요청·장애 주입 없음. 별도 실패로 보존 |
| [mixed-run05](mixed-run05/control.jsonl) | 활성 세션에서 채널을 유지하고 Proxy 읽기 준비를 구분한 Primary 두 조건 통과. 대표 Primary 쌍으로 사용 |

전용 채널 종료의 정확한 원인은 미확정이다. 원본 터널과 원격 DB는 앞선 진단에서 유지됐다. [접속 유지 원장](transport-holder-run01.jsonl)은 종료 범주만 기록하며 `other_omitted`는 원시 stderr를 생략한 범주로서 오류 판정이 아니다. [실패 후 JDBC](failure-run01-jdbc.log), [복구 점검](tunnel-recovery-run01.log)도 구분한다.

실행기 인코딩 선언 오류는 실제 시험 시작 전에 수정했다. 원시 로그를 사후 재구성하지 않았고 실패 원장은 덮어쓰지 않았다. 응답·Hikari100ms·제어 이벤트는 실행 중 기록했다.

## 리비전·재현·안전 경계

- ACK 시험 소스: 커밋 `dfe40490d43ad688b8873a14a817803a9a366632`, SHA-256 `7c692bbf2ae06f51a900f1912b825bde08d28d83261f76afab3d8cb39f8f1bd0`.
- Primary 보완 소스 SHA-256: `0dda2ac9621658ec75e2f7e52a69e4198ceb399dd7531dfa544f8c07d3d13dc8`. 실행 원장의 base 커밋과 추가 시험 소스 리비전을 구분한다. 집계기가 소스·원시 응답 수·최초4개204·실측 모드·풀 최대값을 다시 검사했다.
- 업무 코드 기준 `6e14adf43c35225b7fcd52c2f20befd4398da196` 대비 `backend/src/main/` 변경은 없다. 소스·Gradle 변경은 수동 시험 하네스·태그 제외·시나리오 선택뿐이다.
- 수동 명령: `./backend/gradlew -p backend permissionMixedCommitTest`. Primary만 반복하려면 `PERMISSION_MIXED_SCENARIO=primary-loss`를 설정한다. 기본은 전체 네 조건이다. 전용 loopback JDBC·migration URL·메모리/환경의 시험 비밀번호·새 실행 디렉터리가 필요하다. 기본 CI에서는 별도 태그로 제외한다.
- 각 `case.waiting` 뒤 격리 스키마·strict·동기 후보·Proxy 도착 확인 후 `environment.ready`, 최초4개204의 `ready` 뒤 장애 주입·`fault.active`, 복구 및 Proxy 준비 뒤 `fault.done`, 앱의 `case.done` 뒤 다음 회차를 준비한다. 사설 대상과 자격 증명을 다루는 SSH 실행기는 공개하지 않는다.
- 기존 라이선스 호스트의 별도 컨테이너·데이터·DCS·포트만 변경했다. 원본 패키지의 이미지 캡처는 pause=false이며 패키지는 읽기 전용이다. 새 VM·OS Login 키·IAM 권한을 추가하지 않았다.
- 역할 어댑터 경유 결과이다. 원본 OpenProxy의 동기 역할 직접 지원이나 공급사 지원 성공을 주장하지 않는다.
- [최종 정리](cleanup.jsonl)에 시험 Proxy·DB·DCS·데이터·Valkey·전용 터널·런타임 비밀 제거와 공개 설정/클러스터 확인 결과를 기록한다. 원래 VM·터널은 유지하고 소유가 불명확한 이미지 캐시는 prune하지 않는다.

개인 이메일·비밀번호·JWT·Authorization·비공개 프로젝트/VM/IP는 수집·공유하지 않았다. 원장은 합성 UUID·상태·지연·안전한 역할 별칭을 사용한다.
