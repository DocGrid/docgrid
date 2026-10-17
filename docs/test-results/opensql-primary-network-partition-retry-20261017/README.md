# 동기 strict Primary 분할 오류 진단과 멱등 재전송 시험

## 결론

격리 GCP 시험 환경에서 살아 있는 Primary와 나머지 두 DB 노드 사이의 통신을 차단한 뒤, 앱 A/B를 통한 멱등 HTTP 쓰기 751건을 보냈다. 첫 응답 `201` 585건은 재전송 전에 모두 승격된 DB에서 확인됐다. 첫 응답이 실패하거나 불명확했던 166건은 그 시점에 DB 행이 없었다. 네트워크를 복구한 후 **원본 751개 ID를 같은 입력으로 한 번씩 재전송**하자 기존 585건은 `200`, 없던 166건은 `201`이었고, 최종 DB에서 각 ID가 정확히 1행이었다. 같은 ID에 다른 입력을 보낸 별도 요청은 `409`였으며 DB 원장은 그대로였다.

이는 **이 시험의 관측 구간에서 성공 응답 보존과 멱등 재전송이 성립했다**는 결과다. 분할 중 일부 요청은 실패했고, 재전송 전 서비스 가용성은 유지되지 않았다. 모든 순간에 동시 쓰기 가능 Primary가 없었다는 증명도 아니다.

## 환경·범위

- 실행일: 2026-10-17 UTC(한국시간 2026-10-18). 격리 GCP 내부 LB → 앱 A/B → 시험용 역할 어댑터를 거친 OpenProxy A/B → OpenSQL·Patroni·etcd 3노드. 외부 공개 서비스의 결과가 아니다.
- 시험 시작 시 `synchronous_mode=on`, `synchronous_mode_strict=true`, DB2가 Primary, DB1·DB3이 Replica였다. 이 값과 당시 역할은 실행 직전 확인했다. 로컬 시험 도구 기준 리비전은 `737db9a61237f8f003e50eb6a1da895190273cfd`에 아래 두 도구 수정이 더해진 상태다. 배포 앱 A/B의 정확한 소스 커밋은 이 증거만으로 확정하지 않는다.
- 부하 VM은 내부 LB를 통해 `POST /api/ha-probe/idempotent-writes`에 합성 요청 ID와 동일한 합성 payload를 보냈다. 부하 도구의 HTTP 제한 시간은 10초였다. 시험용 관리자 계정은 로그인이 불가능한 합성 계정이며 완료 후 제거했다.
- 새 시험은 기존 비동기 분할 회차와 다른 요청 ID·원장이다. 첫 HTTP 응답과 재전송 결과를 합쳐 성공률로 계산하지 않았다.

```text
그림 1 — 실제 시험 경로
부하 VM: 요청 ID별 최초 응답을 즉시 원장에 기록
    ↓ 내부 LB (건강 상태를 별도 표본 수집)
앱 A/B: 인증 → 멱등 쓰기 트랜잭션 → 최초 HTTP 응답
    ↓ OpenProxy A/B (시험용 역할 어댑터 경유)
OpenSQL 3노드: 시작 Primary DB2 ↔ Replica DB1·DB3
    ↓ DB2↔DB1·DB3의 노드 간 통신만 양방향 차단
앱→옛 Primary 접속과 별도 제어기→DB 접속은 유지
```

## 회차별 사전 기준과 관측

| 실행 ID | 목적·실행 위치·방법 | 사전 성공 기준 | 관측과 판정 | 개별 증거 |
| --- | --- | --- | --- | --- |
| `ha501base01` | GCP 부하 VM, 정상 멱등 API 5 req/s × 15초 | 미전송 0, 최초 `201` 외 0, DB ID당 1행 | 최초 76/76 `201`, DB 76/76, 중복 0, 미전송 0. 통과 | `ha501base01.log`, `ha501base01-http-events.jsonl`, `ha501base01-db-before.csv`, `ha501base01-audit.json` |
| `ha502diag01` | 같은 경로, 2 req/s × 120초. Primary 분할 약 61초, 자동 원복 180초 설정 | 실제 단절·해제, 최초 `201` 누락 0, 오류 계층 분류 | 241건: `201` 155, `500` 15, `503` 43, `401` 20, 불명 8, 미전송 0. `201` 155건 전부 DB에 있고 중복 0. **진단 회차 통과**, 서비스 오류는 별도 관측 | `ha502diag01-control.log`, `ha502diag01-http-events.jsonl`, `ha502diag01-db-before.csv`, `ha502diag01-app*-diagnostics.jsonl`, `ha502diag01-db*-observer.csv` |
| `ha503part01` | 같은 경로, 5 req/s × 150초. 시작 약 36초 뒤 DB2↔peer 분할, 약 77초 뒤 수동 해제 | 미전송 0, 최초 `201` DB 누락·중복 0, 복구 후 단일 Primary | 751건: `201` 585, `500` 112, `503` 46, 불명 8, 미전송 0. 재전송 전 DB 585행·중복 0; `201` 누락 0. **데이터 보존 기준 통과, 가용성은 실패** | `ha503part01-control.log`, `ha503part01-http-events.jsonl`, `ha503part01-db-before.csv`, `ha503part01-app*-diagnostics.jsonl`, `ha503part01-db*-observer.csv`, `ha503part01-lb-health.csv` |
| `ha503replay01` | 같은 경로, 원본 751개 ID를 30 req/s로 1회씩 재전송 | 기존 행 `200` 585건, 없는 행 `201` 166건, 최종 각 ID 1행 | `200` 585, `201` 166, 기타·불명·미전송 0. 최종 751행, 중복 0. **통과** | `ha503replay01-control.log`, `ha503replay01/재전송-전체-목록.json`, `ha503replay01/재전송-시도.jsonl`, `ha503replay01/재전송-최종-대조.json`, `ha503part01-db-after.csv` |
| `ha503conflict01` | 같은 ID·다른 payload 1회 | HTTP `409`, 최종 DB 불변 | HTTP `409`; 751행·중복 0·감사 파일 내용 불변. **통과** | `ha503conflict01-control.log`, `ha503conflict01-db-audit.json` |
| `localbash01` | 로컬 macOS 작업 트리, `bash -n scripts/opensql/run_ha_probe_k6.sh` | 구문 오류 0 | 종료 코드 0. 통과 | `localbash01-verification.log` |
| `localretry01` | 로컬 macOS 작업 트리, 재전송 검증기 단위 시험 | 14개 시험 모두 통과 | 14/14 통과 | `localretry01-verification.log` |

시간·단위: `ha503part01`의 요청 p50 60 ms, p95 5,011 ms, p99 9,960 ms. 이는 전체 751건의 HTTP 시간 분포이며 장애 구간만의 값이 아니다. LB 건강 상태는 15개 표본 중 13개가 2/2 healthy, 2개가 0/2 healthy였다. 1초마다 모든 내부 순간을 본 것은 아니다.

```text
그림 2 — 본 회차 시간축 (UTC, 관측값)
14:59:00  최초 요청 시작: 5 req/s
14:59:36  DB2↔DB1·DB3 차단 적용 (180초 독립 자동 원복 설정)
14:59:51  DB2에서 peer TCP: etcd 0/2, Patroni 0/2, DB 0/2
14:59:57  LB healthy 0/2 표본
15:00:01  DB3 Patroni primary 표본
15:00:03  LB healthy 0/2 표본
15:00:54  분할 규칙 수동 해제
15:01:40  부하 종료: 751건, k6 종료 코드 0, 미전송 0
15:01:46  분할 규칙 부재 재확인
```

DB2는 분할 중 일부 표본에서 `primary`와 `other` 또는 `unknown` 상태를 보였고, DB3은 15:00:01부터 `primary/running`으로 관측됐다. DB2가 실제 SQL 쓰기를 수락했는지는 이 역할 표본만으로 판정할 수 없다. 따라서 두 노드가 동시에 쓰기 가능하지 않았다고 단정하지 않는다.

## 실패 응답의 위치와 멱등 복구

`ha503part01`의 `500` 112개 요청 ID는 **모두 앱의 허용 목록 진단 이벤트와 연결**됐다. 108건은 트랜잭션 시작(`TX_BEGIN`) 단계, 4건은 커밋 대기(`COMMIT_PENDING`) 단계였다. 진단 이벤트는 예외 유형·SQLSTATE·단계만 남겼다. `COMMIT_PENDING` 4건도 재전송 전 DB에는 없었다. 이 결과는 해당 요청들이 이 시험의 최종 Primary에 반영되지 않았다는 뜻이며, 데이터베이스 내부의 모든 중간 상태를 증명하지는 않는다.

`503` 46건에는 대응하는 앱 진단 이벤트가 없었다. 같은 장애 구간에 내부 LB의 healthy 백엔드가 0/2로 떨어진 표본 2개가 있어 LB의 무건강 백엔드 응답과 시간상 맞는다. 하지만 LB 요청 로그를 수집하지 않았으므로 각 `503`의 반환 주체를 확정하지 않는다. `ha502diag01`의 `401` 20건은 토큰 만료로 단정하면 안 된다. 모두 권한 확인(`AUTHORIZATION`) 단계의 DB 연결 오류 이벤트와 연결됐고 장애 구간에 집중됐다. 본 회차에는 `401`이 없었다.

```text
그림 3 — 같은 요청 ID의 원장 대조
첫 응답 201: 585 ID ──→ 재전송 전 DB 585행 ──→ 재전송 200 ×585
첫 응답 500: 112 ID ┐
첫 응답 503:  46 ID ├─→ 재전송 전 DB 0행 ──→ 재전송 201 ×166
첫 결과 불명: 8 ID ┘
                                                ↓
                                    최종 DB 751 ID / 각 1행
같은 ID·다른 입력 1건 ──→ HTTP 409 ──→ 최종 DB 감사 불변
```

## 재현·검증 명령

사전 조건은 격리 환경, 합성 ADMIN, 내부 LB, 동기 strict 3노드, 독립 자동 원복 타이머다. 주소·토큰·DB 비밀번호는 명령이나 증거에 기록하지 않고 현장에서만 제공했다. 재현할 때는 실행 직전 Primary를 새로 식별해야 하며 특정 VM을 계속 Primary로 가정하면 안 된다.

- 최초 부하: `run_ha_probe_k6.sh <run-id> 5 150s <internal-url> <private-jwt-file> primary-network-partition-idempotent 120`
- 차단·원복: 현재 Primary에서 `primary_partition_guard.py apply <run-id> --rollback-seconds 180`, 관측 후 `revert <run-id>`; `status`와 두 peer 통신 확인.
- DB 감사: OpenProxy의 명시적 읽기·쓰기 트랜잭션으로 `primary_partition_fixture.py audit-idempotent <container> <run-id>`.
- 재전송: `retry_idempotent_ha_probe.py prepare-k6-all`로 원본 이벤트·요약·DB 사전 감사의 해시와 ID 목록 고정 → `run_ha_probe_replay_all_k6.sh` 실행 → `verify-k6-all`로 첫 응답, 재전송, DB 사후 감사 대조.

재전송 검증기의 최종 결과는 `final_pass=true`, `idempotency_pass=true`, `initial_201_missing_before_retry=0`, `final_missing_sent_ids=0`, `db_duplicate_ids_after_retry=0`이다. 여기서 `final_pass`는 이 합성 시험 계약의 통과이지 사용자 서비스 전체의 무손실 보장이 아니다.

## 준비 문제·제한·원복

- SSH 기본 개인키는 암호로 보호돼 자동 접속에 사용할 수 없어, 2시간 만료의 임시 OS Login 키와 **해당 6대 VM에만** 임시 로그인 역할을 부여했다. 완료 후 역할 6개, 새 키 2개, 임시 개인키를 제거했다. 기본 공개키의 중복 등록을 지우는 과정에서 **이미 만료된 동일 공개키 등록 1개도 함께 제거**됐다. 기존에 유효한 다른 접근 권한을 의도적으로 변경하지 않았다.
- 새 프로젝트 DB 노드에는 예전 `credentials.env`가 없어 기존 직접 DB 감사 경로가 실패했다. 현재 OpenProxy에 `BEGIN READ WRITE`를 명시하는 합성 계정 생성·감사 동작을 도구에 추가한 뒤 실행했다. 원본 서비스 DB 데이터는 변경하지 않았다.
- 관측기 시작용 SSH 명령은 백그라운드 자식 때문에 120초에 타임아웃됐지만, 각 DB의 안전 CSV는 240개씩 기록됐다. 관측은 15:01:10까지라 마지막 정상 부하 30초 전체를 덮지는 않는다. 별도 사후 역할 조회에서 DB3 Primary 1대, DB1·DB2 Replica 2대를 확인했다. 사후 역할·분할 규칙 상태는 `ha503part01-final-state.json`에, 임시 접근 정리 상태는 `access-cleanup.json`에 별도로 기록했다.
- 앱 A/B의 오류 진단은 허용된 요청 ID·실패 단계·예외 유형·SQLSTATE만 수집했다. LB 원시 요청 로그, 본문, 내부 IP, JWT, 비밀번호는 기록하지 않았다. 따라서 `503`의 정확한 생성 계층과 모든 순간의 split-brain 부재는 미검증이다.
- 시험 후 분할 규칙은 모두 제거했고, 합성 관리자 계정·JWT·임시 로그인 권한은 제거했다. **VM 7대, DB 데이터, 동기 strict 설정, 시험용 역할 어댑터는 사용자가 재시험을 원해 그대로 유지했다.** 실행 중인 VM 비용도 계속 발생할 수 있다.
