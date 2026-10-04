# OpenSQL etcd 정족수 상실·Patroni failsafe 실측

실행일: 2026-10-04 UTC · 이슈: [#428](https://github.com/DocGrid/docgrid/issues/428) · 범위: **DB 직접 쓰기와 DCS 동작**

## 결론

- 실제 3노드의 동적 설정은 `failsafe_mode=true`, `ttl=30s`, `loop_wait=10s`였다. 장애 전후에 Patroni는 primary 1대와 `streaming` replica 2대, etcd는 3개 endpoint 정상 상태였다.
- etcd 한 멤버만 `SIGSTOP`한 실행은 2/3 정족수를 유지했다. 약 56초의 중단 구간에 합성 DB 쓰기 **48/48건 커밋**, 실행 전체 **70/70건**, 최종 DB 행 **70개·고유 요청 ID 70개**였다.
- etcd 두 멤버를 `SIGSTOP`한 뒤 `ttl`을 지난 구간에도 primary의 DB 직접 쓰기가 계속됐다. **두 번째 중단 뒤 30초 초과·수동 복구 전으로 확정할 수 있는 표본 30/30건**이 커밋됐다. 별도 지속 관측 실행 전체는 **55/55건**, 최종 DB 행 **55개·고유 ID 55개**였다. 같은 시간대 Patroni 파일 로그에서 `failsafe` 키워드가 **9회** 관측됐다.
- 두 etcd 프로세스를 재개한 뒤 endpoint **3/3 정상**, Patroni **primary 1·streaming replica 2**, 내부 HTTP LB **HEALTHY 2/2**, 자동 재개 타이머 **0개 활성**을 확인했다.
- 이번 수치로 **앱 HTTP 201/500·k6 처리량·외부 request_id 원장·연속적인 이중 primary 부재·Patroni REST 전체 멤버 도달 여부**는 판정하지 않는다. 이슈의 HTTP 경로 완료 조건은 별도 실행이 필요하다.

```text
그림 1. 시험 대상과 격리한 경로

                    ┌─ DB node1: PostgreSQL primary + Patroni + etcd ①
SQL probe(1건/초) ──┤
                    ├─ DB node2: streaming replica + Patroni + etcd ② ← SIGSTOP
                    └─ DB node3: streaming replica + Patroni + etcd ③ ← SIGSTOP

한 멤버 시험: ①+② 가동 / ③ 일시정지 → etcd 정족수 2/3
두 멤버 시험: ① 가동 / ②+③ 일시정지 → etcd 정족수 1/3

DB SQL probe는 node1의 컨테이너 안에서 1건씩 autocommit한다.
따라서 앱·Hikari·OpenProxy·HTTP LB의 가용성을 측정한 결과는 아니다.
```

## 안전 관문과 실행 환경

| 확인 항목 | 실행 전 결과 | 해석 |
| --- | --- | --- |
| 제품 | Patroni 4.0.5, etcd/etcdctl 3.6.5 | 현재 설치된 빌드 기준 |
| Patroni 역할 | Leader 1, streaming Replica 2 | 처음과 끝에 확인; 장애 중 모든 순간을 촬영한 것은 아님 |
| DCS | etcd 멤버 3, endpoint health 3/3 | 3멤버의 과반은 2 |
| 동적 설정 | `failsafe_mode=true`, `ttl=30`, `loop_wait=10`, `retry_timeout=10` | 실험 전 읽은 값. 설정을 변경하지 않음 |
| WAL | 시작 시 각 replica의 수신·재생 LSN이 primary와 일치 | 시작 당시 지연 0 관측. 전체 장애 중 지연 보장은 아님 |
| HTTP LB | 앱 2/2 HEALTHY | DB probe의 실제 경로에는 포함되지 않음 |
| 복구 | 최신 etcd snapshot 2,314,272 B; 공식 3.6.5 `etcdutl` 상태 revision 6699·키 10개; 격리 복원본 health `true`, revision 6699 | 운영 DCS 디렉터리와 분리해 실제 기동. [복원 실행 기록](evidence/issue-428/backup-restore-20261004.md) |
| 독립 원복 | node2·3 각각 host-side `systemd` 자동 `SIGCONT` 타이머가 실제 실행되는지 무장·검증 | 노트북 SSH 세션이 끊겨도 일시정지된 프로세스 재개. [가드 기록](evidence/issue-428/guard-20261004.md) |

기존 GCP 부팅 디스크의 READY snapshot은 노드별 **3/2/2개**였으나 이번 작업에서 새 디스크 snapshot을 생성하거나 그 복원을 시험하지 않았다. 새 etcd snapshot은 장애 중 root 전용 권한으로 보관했고, 복원 시험도 **같은 VM의 격리 디렉터리**에서 수행했다. 이를 독립 오프노드 백업 검증이라고 부르지 않는다.

## 실행 결과

| 실행 ID·목적 | 실제 주입·관측 | SQL probe / DB 대조 | 판정·근거 |
| --- | --- | --- | --- |
| [`e428smoke`](evidence/issue-428/e428smoke.md), 정상 probe | 장애 전 3건 | 커밋 3/3, DB 행 3·고유 ID 3 | DB probe 경로 통과 |
| [`e428one`](evidence/issue-428/e428one.md), 한 멤버 중단 | node3 etcd `05:48:16.740Z` 중단 → `05:49:13.011Z` 재개 | 중단 중 48/48 커밋, 전체 70/70; DB 행 70·고유 ID 70 | 2/3 상태에서 DB 쓰기 유지 관측 |
| [`e428two`](evidence/issue-428/e428two.md), 초기 두 멤버 실행 | node2 `05:56:08.442Z`, node3 `05:56:29.678Z` 중단. probe는 `05:56:40.784Z` 종료 | 전체 105/105; DB 행 105·고유 ID 105 | **TTL 이후 판정에는 사용하지 않음**. 두 번째 중단 뒤 11초 만에 종료 |
| [`e428quorum`](evidence/issue-428/e428quorum.md), 지속 정족수 상실 | 두 멤버가 이미 멈춘 `05:57:18.422Z`부터 별도 55개 표본 | TTL 초과·복구 전 확정 표본 30/30; 전체 55/55; DB 행 55·고유 ID 55 | DB 직접 쓰기 지속과 failsafe 로그 키워드 9회 관측. HTTP 무중단 증거 아님 |

```text
그림 2. 두 멤버 중단의 시간 경계(UTC)

05:56:08.442  node2 etcd SIGSTOP
05:56:29.678  node3 etcd SIGSTOP → 3멤버 중 1개만 응답 가능한 상태
05:56:40.784  초기 probe 종료 → TTL 30초에 못 미쳐 판정에서 제외
05:56:59.678  두 번째 중단 뒤 30초 경과
05:57:18.422  별도 지속 probe 시작
05:57:51.958  30번째 쓰기 커밋·DB 30행 대조; 이때까지 수동 복구 미실행
이후          node2·3 etcd SIGCONT, endpoint 3/3 복구
05:58:21.458  별도 probe 종료: 55/55 커밋, DB 55행·고유 ID 55

위 30건은 TTL 초과 구간의 하한 증거다.
복구 명령의 정확한 서버 시각은 별도 계측하지 못했으므로
전체 55건을 모두 ‘정족수 상실 중’ 처리한 것으로 주장하지 않는다.
```

## 명령·관측 위치

| 위치 | 명령 또는 방법(식별자 치환) | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 로컬 제어면 | 공식 etcd 3.6.5 release archive 다운로드 → `SHA256SUMS`와 `shasum -a 256` 비교 | 두 SHA-256 일치 | 다른 버전·손상 도구 배제 |
| node1 VM + DB 컨테이너 | `etcdctl snapshot save <root-only-snapshot>` → `etcdutl snapshot status` | 2,314,272 B, revision 6699, 키 10개 | 현재 DCS의 백업 생성·파싱 |
| node1 VM 격리 디렉터리 | `etcdutl snapshot restore` → 127.0.0.1의 격리 포트에서 etcd 기동 → `etcdctl endpoint health/status` | health `true`, revision 6699 | 운영 클러스터에 합류하지 않고 복원 가능 확인 |
| node2·3 VM 호스트 | `etcd_fault_guard.sh arm/status/recover/cancel` | 양쪽 타이머 독립 실행·종료 확인 | 접속 단절 시에도 정지 상태를 해제할 수 있음 |
| node1 VM 호스트 | `etcd_write_probe.sh <run> <samples>` | 매 표본마다 synthetic ID와 성공/불명을 허용 필드로 실시간 기록 | 앱 계층 없이 DCS 영향을 분리 |
| node2·3 VM 호스트 | `etcd_fault_guard.sh pause` → 관측 → `recover` | etcd PID만 SIGSTOP/SIGCONT; Patroni·PostgreSQL·VM은 계속 실행 | VM 장애나 서비스 정상 종료 시험으로 해석하지 않음 |
| node1 VM + DB 컨테이너 | `etcd_probe_reconcile.sh <run>` | 각 실행의 행 수 = 커밋 수 = 고유 ID 수 | 결과 불명·중복 관측 0건 |
| 세 DB VM·GCP LB | endpoint health, Patroni 멤버, LB health | 종료 뒤 3/3, primary 1·replica 2, LB 2/2 | 원복 확인 |

## 실패·남은 검증

1. 최초 격리 etcd 기동은 SELinux가 임시 디렉터리 실행 파일을 거부해 실패했다. 공식 SHA-256 검증 바이너리를 SELinux 실행 레이블이 있는 위치에 설치한 뒤 동일 복원본의 health `true`를 확인했다. 운영 etcd는 이 과정에서 수정하지 않았다.
2. `e428two`는 105건 모두 커밋됐지만 두 번째 멤버 정지 후 11초 만에 끝났다. 이 수치를 failsafe의 TTL 초과 증거로 쓰지 않고 `e428quorum`을 별도 실행했다.
3. 두 멤버의 프로세스 상태·기존 3멤버 구성으로 정족수 1/3을 판정했다. **중단 중의 etcdctl endpoint health 응답 자체는 별도 캡처하지 않았다.** Patroni 로그 원문은 내부 식별자 노출 위험 때문에 게시하지 않고 시간대별 관련 행 12개, `failsafe` 언급 9개라는 집계만 남겼다.
4. 역할은 주입 전후에 확인했으며 **장애 중 전 노드의 매 순간 역할을 표본화하지 않았다.** 따라서 “split-brain이 어떤 순간에도 없었다”는 절대 보장이 아니라, 종료 뒤 이중 primary **관측 없음**이라고 표현한다.
5. 이슈의 **GCP 내부 k6 → HTTP LB → 앱 → OpenProxy 경로**, HTTP 201/500/결과 불명, DB 밖 클라이언트 원장은 이번 실행에 포함되지 않았다. DB 노드 내부 SQL probe는 이 경로의 대체 증거가 아니다. HTTP 실행을 마쳐야 이슈 #428의 원래 완료 조건을 닫을 수 있다.

## 원복·정리

종료 후 etcd endpoint 3/3, primary 1·streaming replica 2, LB 2/2를 재확인했다. node2·3의 자동 재개 타이머는 모두 해제했다. 시험용으로 생성한 **현재 etcd snapshot과 격리 복원 데이터**는 원복 확인 후 해당 시험 전용 디렉터리에서 삭제했다. 따라서 이 snapshot 자체는 **더는 복구에 사용할 수 없다**. 기존 GCP 부팅 디스크 snapshot은 건드리지 않았다. 세 VM의 만료형 임시 SSH 공개키는 인스턴스 메타데이터에서 제거해 남은 인스턴스 키 항목 0개를 확인했고, 로컬 개인키도 삭제했다. DB VM과 앱 VM은 계속 실행 중이다. 대조 재현을 위한 합성 `ha_probe_writes` **233행(3+70+105+55)**은 남겨 뒀다.

관련 도구는 [호스트 자동 재개 가드](../../scripts/opensql/etcd_fault_guard.sh), [DB 쓰기 probe](../../scripts/opensql/etcd_write_probe.sh), [집계 대조](../../scripts/opensql/etcd_probe_reconcile.sh)다. 시험 목적 외 SQL은 실행하지 않았고, DB에 남긴 것은 합성 `run_id`의 probe 행뿐이다.
