# OpenSQL etcd 정족수 상실 중 앱 HTTP 쓰기·DB 대조

## 결론과 범위

2026-10-04 실제 GCP 시험 환경에서 **GCP 내부 k6 → 내부 HTTP LB → 백엔드 A/B → Hikari → OpenProxy A/B → OpenSQL 3노드** 경로를 시험했다. 정상 기준선, etcd 한 멤버 일시정지, 두 멤버 동시 일시정지를 각각 별도 실행 ID로 기록했다. 네 실행의 HTTP 201 **9,007건**은 DB 밖 원장의 `request_id`와 최종 primary의 행 수가 모두 1:1로 일치했다. HTTP 오류·결과 불명·k6 미전송·201 누락·중복은 각 실행에서 0건이었다.

두 멤버가 중단된 실행은 독립 systemd 가드가 자동 재개하기 전까지 약 **100초**간 정족수를 잃었다. 두 번째 중단 시각으로부터 Patroni `ttl=30s`가 지난 후, 첫 자동 재개 직전까지의 보수적 구간에서 **201 응답 2,100건**을 기록했다. 이것은 `failsafe_mode=true` 환경에서 *이 실행의* 앱 쓰기가 계속됐다는 관측이다. 모든 시점에 쓰기 가능한 primary가 정확히 한 대였다는 연속 증명이나, 임의의 네트워크 분할에서도 RPO=0이라는 보장은 아니다.

이 문서는 [PR #429](https://github.com/DocGrid/docgrid/pull/429)의 DB 직접 쓰기 시험에서 범위 밖이던 HTTP 경로를 보완한다. DB 직접 쓰기의 수치와 이번 HTTP 수치를 합산하지 않는다.

## 시험 환경과 사전 관문

| 구성 | 실행 당시 확인한 값 | 이 시험에서의 역할 |
| --- | --- | --- |
| OpenSQL DB VM | 3대, Patroni 4.0.5, etcd 3.6.5, OpenSQL v3.17.8.7 | primary 1·streaming replica 2, WAL 지연 0 MB |
| OpenProxy | 2대, 1.1.3 | 앱 연결 경로 |
| 백엔드 | 2대, 동일 JAR SHA-256 `c0180191121208d105f422cb64c57e325efc63a1c568c4c81a73d70cc7b8d1aa` | `opensql-ha,ha-probe` 프로필, 앱/LB 2/2 HEALTHY |
| 부하 발생기 | 별도 GCP 내부 VM 1대 | k6와 1초 호스트 계측, DB 밖 요청 원장 |
| Patroni | `failsafe_mode=true`, `ttl=30s`, `loop_wait=10s`, `retry_timeout=10s` | 정족수 상실 시 예상 동작의 기준 |
| 복구 장치 | 현재 etcd snapshot checksum 확인·격리 복원 성공, 각 장애 VM의 독립 systemd 자동 재개 타이머 | 시험 연결이 끊겨도 etcd 재개. snapshot은 시험 후 제거 |

비밀을 제거한 설정은 [redacted-config.json](evidence/issue-428-http/redacted-config.json)에 보관했다. 원장 manifest의 설정 SHA-256은 `5bf778d5005088b7a7b149b55a2b26ab4df5dac393790a29cef839d349555d28`이다. 실행 기반 커밋은 `20c813bb2f5cff6dbcdbaf1099ad0489ea95f4d6`이고, 실행 당시 배포한 k6 runner/JS/계측기 SHA-256은 각각 `cee44dedb1223c5cd5f1960f26e0fada8bcf92b8408e2ff51c9ac60841214987`, `f47567887d495cb782722f5780271bfd33dac516827a3b9f68ff2535943f0eb3`, `84e63cf3968fe9f0e163782553d4e9c068b4559887f3791f1adbbb60a60114d2`다. runner의 etcd 목적 코드 변경은 시험 뒤 PR에 포함하며, 앱 JAR은 이 PR에서 다시 빌드하지 않았다.

```text
앱 HTTP 경로와 외부 원장의 경계

GCP 내부 k6 ── request_id / JWT ──▶ 내부 HTTP LB
      │                                 ├─ 백엔드 A ─ Hikari ┐
      │                                 └─ 백엔드 B ─ Hikari ┤
      │                                                    ▼
      │                                              OpenProxy A/B
      │                                                    ▼
      │                                       OpenSQL primary + replica 2
      │
      └─ 201·오류·timeout·미전송을 DB 밖에서 기록
                         │
                         └─ 복구 후 primary의 request_id별 행 수와 join
```

## 실행·대조 결과

모든 시각은 UTC이며, 한국 표준시는 **UTC+09:00**이다. 실행별 원본과 한글 로그는 각 실행 ID 폴더에서 따로 확인할 수 있다.

| 실행 ID·목적 | 부하·장애 | HTTP/201 | 500·결과 불명·미전송 | DB 행·201 누락·중복 | 전체 p95 / p99 |
| --- | --- | ---: | ---: | ---: | ---: |
| [e428hsmoke01](evidence/issue-428-http/e428hsmoke01/실행-결과.md) · 접속 smoke | 1 req/s × 5초 | 6 / 6 | 0 / 0 / 0 | 6 / 0 / 0 | 350.14 / 378.80 ms |
| [e428hbase01](evidence/issue-428-http/e428hbase01/실행-결과.md) · 정상 기준선 | 30 req/s × 60초 | 1,801 / 1,801 | 0 / 0 / 0 | 1,801 / 0 / 0 | 57.58 / 61.69 ms |
| [e428hone01](evidence/issue-428-http/e428hone01/실행-결과.md) · 한 멤버 중단 | 30 req/s × 90초 | 2,700 / 2,700 | 0 / 0 / 0 | 2,700 / 0 / 0 | 54.85 / 58.30 ms |
| [e428htwo01](evidence/issue-428-http/e428htwo01/실행-결과.md) · 두 멤버 중단 | 30 req/s × 150초 | 4,500 / 4,500 | 0 / 0 / 0 | 4,500 / 0 / 0 | 54.75 / 58.12 ms |

이 p95/p99는 **전체 실행**의 수치다. 장애 구간만의 별도 지연 분포나 더 높은 처리량에서의 결과로 해석하지 않는다. 네 실행은 길이와 warm-up이 달라 p95의 작은 차이를 failsafe의 성능 이득으로 해석할 수도 없다. k6 종료 코드와 1초 표본기/검증 종료 코드는 각 실행에서 0이었다.

## 명령·위치·결과의 연결

| 실행 위치 | 실행한 방법·명령 구조 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| GCP 내부 부하 VM | `run_ha_probe_k6.sh <run_id> <rate> <duration> <내부 LB probe URL> <0600 JWT 파일> <목적코드> 80` | 실행마다 안전 이벤트·1초 CSV·k6 요약 별도 저장 | 앱·프록시·DB를 모두 지난 HTTP 요청이다. JWT와 내부 URL은 로그에 쓰지 않았다 |
| DB VM 호스트 | `etcd_fault_guard.sh arm|pause|status|recover|cancel <컨테이너> <run_id> <초>` | 한 멤버 또는 두 멤버의 etcd PID만 SIGSTOP, 독립 타이머에서 SIGCONT | VM/Patroni/PostgreSQL 종료 시험과 구분 |
| DB primary | 실행 ID로 제한한 `SELECT request_id, COUNT(*) ... GROUP BY request_id` 읽기 전용 내보내기 | 6·1,801·2,700·4,500개의 고유 ID가 각 1행 | 응답과 DB 최종 상태의 직접 대조 |
| 로컬 증거 정리 | `import_completed_ha_k6.py` → `ha_evidence.py verify` → `reconcile_ha_probe.py` | 네 원장 모두 complete, acknowledged 9,007건, 201 누락·중복·고아 0 | 원본 이벤트 시각을 유지해 사후 가져왔으며 새 HTTP 요청은 보내지 않음 |
| GCP 관리 API·DB VM | etcd endpoint health, Patroni role/replication, LB backend health 재조회 | 최종 etcd 3/3, primary 1·streaming replica 2, LB 2/2 | 복구 완료. 장애 중 연속 역할 증명과는 다름 |

최초 smoke 호출은 URL 인수의 셸 따옴표 처리 오류로 runner **입력 검증 단계에서 중단**됐다. run 디렉터리·HTTP 요청·DB 쓰기는 생성되지 않았다. 같은 목적으로 명령을 고쳐 `e428hsmoke01`을 실행했고 그 성공 결과만 수치 표에 포함했다.

## 두 멤버 중단의 시간축

```text
UTC                         etcd 정족수 / Patroni / HTTP
07:17:28.504   k6 시작      etcd 3/3, HTTP 30 req/s
07:18:12.601   멤버 C STOP  정족수 유지 가능
07:18:13.589   멤버 B STOP  두 PID 모두 paused 확인 → 과반 상실
07:18:26.889                  Patroni 로그에 failsafe 키워드 첫 관측
07:18:44.014   TTL 경계     두 번째 중단 + 30초 + 여유
       │         └─ 이 보수적 구간의 HTTP 201 = 2,100건
07:19:47.772                  failsafe 키워드 마지막 관측
07:19:53.995   집계 경계     첫 자동 재개 직전
07:19:54.014   멤버 B CONT  독립 systemd 가드가 자동 재개
07:19:54.702   멤버 C CONT  독립 systemd 가드가 자동 재개
07:19:58.489   k6 마지막    전체 4,500 HTTP / 4,500 201
이후            DB 대조      4,500 ID 모두 1행; etcd 3/3, LB 2/2
```

두 PID가 함께 멈춘 상태에서 `etcdctl --command-timeout=2s endpoint health --cluster`는 **종료 코드 1**로 실패했다. 이는 health API의 정상 응답을 얻지 못했다는 증거이며, 각 endpoint의 연속 1초 건강도 표본은 아니다. Patroni 로그는 개인·내부 식별자를 포함할 수 있어 원문을 공개하지 않고 시간과 `failsafe` 키워드 관측 27건만 기록했다.

## 판정 가능한 것과 한계

- **관측된 것:** 한 멤버 중단과 두 멤버 동시 중단에서 목표 30 req/s의 모든 HTTP 요청이 전송됐고 201을 받았다. 두 멤버 중단이 TTL보다 긴 보수적 구간에도 앱 쓰기 2,100건이 성공했다. 최종 primary에는 모든 201의 ID가 정확히 1행이다.
- **관측되지 않은 것:** 전 노드의 Patroni 역할을 고빈도·연속적으로 채집하지 못했다. 전후에는 primary 1대였지만, 그 사이 모든 순간의 split-brain 부재를 이 자료만으로 수학적으로 증명하지 않는다. 한 멤버 중단에서는 health 명령 시점에 자동 가드가 이미 재개해 장애 중 2/3 직접 스냅샷을 놓쳤다.
- **보장하지 않는 것:** 이 시험은 primary 장애나 async 복제 지연을 넣지 않았다. 따라서 모든 장애에 대한 RPO 0, 정확한 HTTP 쓰기 RTO, 더 큰 부하에서의 지연 상한은 여기서 주장하지 않는다.
- **남은 일:** 엄밀한 연속 역할·endpoint 시계열이 필요하다면, 별도 부하 실행에서 안전한 빈도의 독립 수집기를 먼저 마련한 뒤 재시험해야 한다. 이번 사용자의 HTTP 응답·DB 대조 요구는 위 실행으로 완료했다.

## 정리

합성 관리자 4명·단기 JWT를 삭제했고 fixture 정리 타이머도 해제했다. 두 etcd는 running, 복구 타이머 0개, 최종 etcd 3/3·primary 1·streaming 2·LB 2/2를 확인했다. 검증에 쓴 임시 etcd snapshot/격리 복원과 임시 바이너리·JWT 생성 도구·가드를 제거했으며, 여섯 VM의 임시 SSH 키도 제거했다. 기존 VM과 요청 원본 로그는 후속 시험을 위해 유지했다.
