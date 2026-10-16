# OpenSQL 지정형 동기·quorum 장애 비교

**역할 호환 어댑터를 경유한 실제 격리 OpenSQL·OpenProxy에서 4회차가 통과했습니다.** 최초 요청 192개 중 174개는 204였고, Primary 상실 때의 503 18개는 같은 키 재시도로 모두 확정했습니다. 최초 204 키 174개가 모두 보존됐으며 최종 요청 키는 192개, 회수 대상 권한 행과 최종 인가 허용은 0개입니다.

> **원본 OpenProxy 그대로는 두 동기 모드 모두 시작에 실패합니다.** `sync_standby`와 `quorum_standby` parser 오류를 실제 관측했으며 [호환 어댑터와 원본 실패 기록](../patroni-role-compatibility-20261017/README.md)에 분리했습니다. 아래 표는 어댑터 경유 결과이며 공급사 공식 지원이나 공개 배포 완료를 뜻하지 않습니다.

## 실제 결과

| 회차 | 최초 204 | 최초 503 | 같은 키 재시도 | 최종 키 대조 | 최초 응답 p95 | 종료 확인→쓰기 재개 | Hikari active/pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [지정형 · Standby 1대 중단](designated-standby-run05/control.jsonl) | 48/48 | 0 | 0 | 48/48 | 2906.4 ms | 3.527초 | 2/0 |
| [quorum · Standby 1대 중단](quorum-standby-run01/control.jsonl) | 48/48 | 0 | 0 | 48/48 | 2920.4 ms | 3.474초 | 2/0 |
| [지정형 · Primary 상실](designated-primary-run01/control.jsonl) | 39/48 | 9 | 9 | 48/48 | 5018.8 ms | 26.067초 | 2/2 |
| [quorum · Primary 상실](quorum-primary-run01/control.jsonl) | 39/48 | 9 | 9 | 48/48 | 5019.7 ms | 25.902초 | 2/2 |

[집계 원장](aggregate.json), [판정·시간 정의](measurement-protocol.md).

- 각 회차는 합성 권한 48개·동시성 2이며 동일 애플리케이션/시험 코드 `bb3c73521155a64e9675a28a32c9f673f9a47e0f`를 사용했습니다.
- 네 회차 모두 strict=true, synchronous_node_count=1, on. 최초 Primary·호스트·스키마·데이터량을 같게 초기화했습니다. 지정형은 실제 sync 후보 1대, quorum은 실제 ANY 1 후보 2대를 SQL로 확인했습니다.
- Primary 자동 승격 관측은 지정형 **20.385초**, quorum **18.695초**입니다. 쓰기 재개는 각각 **26.067초**, **25.902초**입니다. 표본 1회씩이며 이 차이로 성능 우위를 일반화하지 않습니다.
- 장애 중 204 뒤 별도 인가 확인이 불가능한 경우는 Primary 회차마다 **2건**이었습니다. 허용으로 추정하지 않았으며 별도로 집계했습니다. `forbidden_content` 원시 카운터는 **최종 인가가 true인 횟수**이고, GCP 네 조회 API의 실제 본문 반환량을 계측한 값이 아닙니다.
- 503은 롤백 확정으로 표현하지 않았습니다. 같은 요청 키의 WAL 재주장과 독립 JDBC 키 감사로 최종 상태를 대조했습니다.

## 실행 경로

```text
로컬 JVM / 실제 PermissionController·Facade·JPA·Hikari
  │  UUID 요청 키 + 합성 문서 단건 권한 회수, 동시성 2
  ▼
IAP 연결 → 격리 OpenProxy → 현재 Primary의 삭제·캐시·Outbox·요청 원장
              │                     │
              │                     └─ strict / on → 동기 후보 ACK 후 204
              └─ 읽기 전용 역할 어댑터 → native Patroni의 fresh scope/역할

외부 JSONL 원장: 시각·논리 표본·키·응답·지연을 실제 응답마다 기록
  → 시험 컨테이너 강제 종료 → 자동 승격/재가입 관측
  → 503은 같은 키 재시도 → 현재 Primary에서 실제 키별 대조
```

단일 JVM의 MVC 호출이며 공개 HTTP 로그인·브라우저·LB·실제 모델 경로는 포함하지 않습니다. DB와 OpenProxy는 실제 라이선스 호스트의 별도 컨테이너·데이터·포트·DCS namespace입니다. 표의 node 별칭은 실제 인프라 이름이 아닙니다.

## 원본 증거

각 디렉터리에 `app.log`, 실제 응답 `requests.jsonl`, 장애 제어 `control.jsonl`, `measurement.json`, `summary.txt`, 독립 `key-reconciliation.log`가 있습니다. 원시 회차를 덮어쓰지 않았습니다.

- [지정형 Standby](designated-standby-run05/requests.jsonl) · [키 대조](designated-standby-run05/key-reconciliation.log)
- [quorum Standby](quorum-standby-run01/requests.jsonl) · [키 대조](quorum-standby-run01/key-reconciliation.log)
- [지정형 Primary](designated-primary-run01/requests.jsonl) · [키 대조](designated-primary-run01/key-reconciliation.log)
- [quorum Primary](quorum-primary-run01/requests.jsonl) · [키 대조](quorum-primary-run01/key-reconciliation.log)

## 준비 실패와 재시험

| 실행 | 관측 | 처리 |
| --- | --- | --- |
| preflight JDBC 01–04 | native DB 경로는 읽혔으나 원본 Proxy 연결 실패 | 로그 폴더 권한 문제를 분리한 뒤 native 역할 parser 결함 확인 |
| [preflight JDBC 05](preflight-jdbc-run05.log) | 어댑터 경유 두 Proxy에서 ANY 1·Primary·streaming 2대 확인 | 본 비교로 진행 |
| [designated-standby-run01](designated-standby-run01/control.jsonl) | streaming 1대라 사전 기준 실패 | 부하·장애 주입 미실행, SQL readiness 대기 보완 |
| [designated-standby-run02](designated-standby-run02/control.jsonl) | 강제 종료 후 시험 socket 잠금이 남아 재기동 실패 | 정지된 시험 컨테이너의 전용 socket만 정리 |
| [designated-standby-run03](designated-standby-run03/app.log) | WAN 스키마 초기화·fixture 준비가 180초 준비 제한 초과 | 장애 주입 미실행. 뒤늦게 시작된 부분 응답 10개는 모두 204였으나 시험을 중단했으며 최종 4회 집계에서 제외. 사전 배치 준비로 분리 |
| [designated-standby-run04](designated-standby-run04/app.log) | 48개 204 뒤 검증용 autocommit 조회가 standby에 도착해 Primary 단언 실패 | 최종 대조도 read-write Transaction으로 수정. [실패 회차 키 감사](designated-standby-run04/post-failure-key-audit.log)는 통과 판정에 재사용하지 않음 |
| designated-standby-run05 | 수정된 동일 시험과 독립 키 감사 통과 | 최종 집계에 사용 |

추가 준비 과정에는 기존 RSA SSH 인증 실패, 임시 만료 키 사용, Docker commit 출력 형식/시간 제한, 읽기 전용 패키지의 로그 경로 준비 문제가 있었습니다. 초기 이미지 캡처 1회에는 Docker 기본 pause가 사용됐고 이후에는 pause=false를 적용했습니다. 공개 HTTP 영향은 계측하지 않았습니다. 공개 DB 설정 hash는 모든 제어 단계에서 일치했습니다.

## 재실행

1. 정식 사용 가능한 기존 라이선스 호스트에 별도 시험 데이터·DCS namespace·포트로 3노드를 준비합니다. 공급사 파일·라이선스·실제 인프라 설정은 이 저장소에 포함하지 않습니다.
2. `patroni_role_compatibility.py`를 시험 scope와 private IPv4에만 연결합니다. 시험 OpenProxy의 Patroni 조회 포트는 해당 어댑터를 가리켜야 합니다.
3. 전용 루프백 JDBC URL·별도 migration URL·비공개 접속 설정과 `PERMISSION_HA_RUN_DIR`, `PERMISSION_HA_MODE`, `PERMISSION_HA_SCENARIO`를 준비하고 `./backend/gradlew -p backend permissionHaTest`를 실행합니다. Task는 전용 loopback Proxy URL만 허용합니다.
4. 실행 디렉터리의 `ready` 뒤에 시험 컨테이너만 중단하고, 실제 승격·재가입 관측 뒤 `fault.done`을 기록합니다. 제어 시각은 별도 JSONL에 남겨야 합니다.
5. 같은 JDBC 드라이버로 `PermissionLedgerReconcile.java`를 실행해 실제 외부 요청 키를 다시 대조합니다. 호스트 접속·자원 생성 제어 프로필은 비공개로 분리했습니다.

## 운영 판단과 검증 한계

- 이번 조건에서는 quorum의 뚜렷한 우위를 관측하지 못했습니다. 한 Standby 상실은 두 모드 모두 48개 204를 유지했고 Primary 상실은 두 모드 모두 9개 503과 같은 키 재시도로 수렴했습니다.
- 원본 OpenProxy 역할 호환 문제가 있으므로 **현재 공개 클러스터의 동기/quorum 전환은 보류**합니다. 동기 확정 기능 기본값 false이며 운영 설정·배포를 이 시험으로 바꾸지 않았습니다.
- 실제 OpenSQL 자동 승격을 시험했지만 후보 지정 수동 전환, 네트워크 분할, 임의 복제 지연, 동기 후보 전체 상실은 이 GCP 비교 범위 밖입니다. 사전 패키지 읽기에서 확인한 #3310 핵심 패치 부재를 해결했다고 주장하지 않습니다.
- 로컬 시험의 COMMIT 경고·응답 유실 모델과 이번 GCP Primary 중단을 구분합니다. GCP에서 COMMIT 후 HTTP 응답만 차단한 별도 회차는 실행하지 않았습니다.
- 로컬에서는 상세·본문·파일·새 검색 MVC 응답을 검증했습니다. GCP에서는 회수·최종 인가·원장 대조를 검증했습니다. 실제 모델·검색 품질·공개 로그인·GCS는 포함하지 않았습니다.
- 기존 VM의 물리 자원을 공유하고 IAP 지연이 포함됐습니다. 전역 RPO=0·공개 서비스 전체 RTO·성능 SLA·공급사 공식 지원으로 확장하지 않습니다.
- 시간 정의 보완은 원시 원장에 동일 규칙을 적용한 사후 집계이며 원시 `measurement.json`의 fault-requested 기준 값도 보존했습니다.

## 정리 확인

[정리 원장](cleanup.jsonl)에 실제 작업 결과를 시간순으로 보존했습니다.

- 격리 GCP 컨테이너 3개·전용 데이터 경로 3개·확인된 시험 이미지 3개를 제거했습니다. 전용 DCS 키는 0개입니다.
- 로컬 fixture 컨테이너 3개와 전용 Docker 네트워크 1개를 제거했습니다.
- 30분 임시 OS Login 키는 이미 만료·부재 상태였고 일치하는 키 0개를 다시 확인했습니다. 로컬 개인키·비밀 파일을 제거하고 전용 SSH 제어 연결 3개를 종료했습니다. 기존 개인키·터널은 유지했습니다.
- 공개 클러스터는 최종 조회에서 leader 1대·streaming replica 2대, 기존 비동기 설정으로 확인했습니다. 원본 설정 hash도 3대 모두 같았습니다. 기존 VM은 정지하지 않았습니다.
- 생성 경위가 확정되지 않은 이전 이미지 캐시는 broad prune하지 않았습니다. 따라서 호스트의 모든 이미지 캐시가 0개라고 주장하지 않습니다.
