# 대시보드 WebSocket 재연결·최신 상태 복구 검증

## 결과와 범위

2026-10-02 KST에 GCP 시험 환경의 백엔드 B 재시작과 공용 Redis 단절을 주입했다. 최초 구현은 백엔드 재시작 후 다시 구독했지만, Redis 단절 중 받은 STOMP `ERROR`를 영구 중단으로 처리하여 180초 안에 복구하지 못했다. 이를 수정한 뒤 같은 유형의 Redis 단절에서 재연결, HTTP 요약 재조회, 새 STOMP 메시지 수신까지 **6,942ms**가 걸렸다. 이는 한 번의 관측값이며 SLA나 무중단 보장은 아니다.

이번 변경은 프런트엔드의 대시보드 실시간 연결에만 적용한다. 서버의 Redis Pub/Sub는 영속 큐가 아니므로, 새 STOMP 구독 직후 `/admin/dashboard/summary`를 HTTP로 다시 읽어 놓친 상태를 보정한다. 기존 30초 HTTP 폴링은 유지한다. 백엔드 재시작 시험의 122,116ms에는 의도적인 중단 시간과 JVM 기동, 최대 30초 재시도 대기가 포함된다.

```text
그림 1 — 변경 전/후의 복구 경로

변경 전: B 또는 Redis 장애 → WebSocket 종료 → 화면 POLLING
                                   └─ 새 CONNECT 없음

변경 후: B 또는 Redis 장애 → 화면 POLLING → 제한된 backoff로 CONNECT
                                            │
                         STOMP CONNECTED ──┴─→ SUBSCRIBE
                                               → HTTP 최신 요약 조회
                                               → LIVE + 다음 MESSAGE 수신
```

WebSocket 메시지는 상태 변경 신호이며, 화면 데이터의 기준은 관리자 HTTP API다. 연결이 복구될 때까지 폴링으로 화면을 유지한다.

## 변경 내용

| 구성 요소 | 변경 | 이유 |
|---|---|---|
| `frontend/app/lib/dashboard-socket-connection.ts` | 연결 세대, 중복 실패 방지, 지수 backoff(기본 1~30초, ±20% jitter), CONNECTED 직후 snapshot 요청, STOMP 오류 후 HTTP 재확인 | 일시 장애와 명시적인 권한 거부를 구분하면서 재구독한다. |
| `frontend/app/lib/useDashboardSocket.ts` | 브라우저 훅이 연결 제어기를 사용하고 인증 만료 이벤트에 즉시 정리한다. | 기존 화면 API와 30초 폴링을 유지하면서 재연결을 추가한다. |
| `frontend/tests/dashboard-socket-reconnect.test.ts` | 결정적 가짜 소켓·타이머로 중복 구독, 재시도, 권한 거부, 토큰 변경, cleanup을 검사한다. | 실제 시간에 의존하지 않는 회귀 시험을 확보한다. |
| `scripts/opensql/dashboard_frontend_reconnect.mjs` | 같은 연결 제어기를 실제 GCP WebSocket에 붙이고 allowlist 이벤트만 JSONL로 기록한다. | 백엔드/Redis 장애의 실제 프런트엔드 복구를 관측한다. |

```text
그림 2 — STOMP ERROR의 분기

서버 ERROR 수신
  ├─ 기존 소켓 닫기 → POLLING (이전 구독으로 계속 수신하지 않음)
  └─ 관리자 HTTP /admin/dashboard/summary 재확인
       ├─ 401 또는 403 → 재시도 중단 (이전 ADMIN 권한으로 우회하지 않음)
       ├─ 200 → backoff 후 새 CONNECT/SUBSCRIBE
       └─ HTTP 자체가 일시 불가 → backoff 후 다시 시도

토큰 교체·로그아웃·화면 언마운트 → 타이머 취소, 소켓 종료
```

HTTP 200은 그 시점의 관리자 권한 확인 결과이지, Redis 장애가 이미 끝났다는 보장이 아니다. 그래서 재접속은 계속 제한된 간격으로 시도한다.

## 실행 환경과 사전 조건

| 위치 | 이번 시험에서 사용한 역할 | 상태/범위 |
|---|---|---|
| GCP 시험 프로젝트 | OpenSQL DB VM 3대, OpenProxy 2개 인스턴스, 백엔드 VM A/B 2대, 공용 Redis VM 1대, 부하·관측 VM 1대 | 총 실행 VM 7대. 시험 전용. 내부 IP·프로젝트 ID는 공개하지 않는다. |
| 백엔드 B | PR #383 기반 시험용 JVM과 WebSocket fixture | 장애 주입 후 재시작. 시험 뒤 fixture 프로세스·시험 계정 정리. |
| 공용 Redis | A/B의 공통 Pub/Sub | `systemctl stop` 후 8초 뒤 start, 마지막 active 확인. |
| 로컬 작업 컴퓨터 | 프런트엔드 코드·결정적 시험·B로의 암호화 SSH 터널 | 토큰은 보호된 임시 파일에서만 읽고 로그에 쓰지 않는다. |

GCP 시험은 실제 대시보드 메시지의 수신과 관리자 HTTP 응답을 관측한다. 브라우저 UI 렌더링 자체나 다중 프런트엔드 브라우저의 체감 시간은 별도로 재현하지 않았다.

```text
그림 3 — 백엔드 B 재시작 시험의 시간축 (재실행 r3)

클라이언트       B 백엔드 JVM              관리자 HTTP
   │ LIVE + MESSAGE 수신
   │                  ├─ 의도적으로 종료
   ├─ POLLING        │
   ├─ CONNECT 재시도 ├─ 다시 기동
   ├─ CONNECTED ────▶│
   ├─ SUBSCRIBE ────▶│
   ├─────────────────┼──────────────▶ 요약 200, 문서수 0
   └─ 새 MESSAGE 수신

처음 POLLING → 재연결 후 새 메시지: 122,116ms
※ 이 값에는 의도적인 다운타임과 기동 시간이 포함된다.
```

## 실행과 관측

아래 명령은 값을 가린 재현 형태다. 실제 토큰·암호·내부 주소는 명령 문자열이나 기록물에 남기지 않았다. 개별 run의 안전한 이벤트 로그는 [`evidence/issue-384/`](./evidence/issue-384/)에 분리했다.

| run ID / 위치 | 실행 방법·목적 | 핵심 결과 | 판정·해석 |
|---|---|---|---|
| `fix384-gcp-reconnect-r1` / 로컬→GCP B | `dashboard_frontend_reconnect.mjs --ws-url <B 터널> --http-url <B 터널> --token-file <보호 파일>`; B 장애 전 연결 | 처음부터 `CONNECTING↔POLLING` 16회, LIVE 0회. IAP 직접 포트 터널이 4003으로 거부됨 | **환경 준비 실패**. 제품 동작 판정에 사용하지 않음. SSH 포트 전달로 변경. |
| `fix384-gcp-reconnect-r2` / 로컬→GCP B | B fixture 종료·재기동 중 실제 제어기 관측 | LIVE 2회, 새 메시지 수신, 복구 121,426ms. 그러나 관측 스크립트가 종료 후 비동기 기록을 시도해 `EBADF`, 프로세스 exit 1 | **제품 복구 관측·하네스 실패**. 기록기 종료 가드를 추가하고 별도 r3 재실행. |
| `fix384-gcp-reconnect-r3` / 로컬→GCP B | 동일 절차 재실행 | LIVE 2회, HTTP snapshot 68회(성공 68), STOMP 메시지 68회, 재연결 후 새 메시지 1회, 복구 122,116ms, exit 0 | **PASS**. 의도적 중단 후 복구 확인. |
| `fix384-gcp-redis-r2` / GCP 내부 관측 VM | B 구독 5개를 유지하며 공용 Redis를 8초 중지·재시작 | 중단 전후 수신 490건, 연결 종료·오류 5건, B 앱 JVM은 생존 | Redis 단절은 클라이언트 소켓을 끊었다. 490건은 복구 후 지속 수신이 아니다. |
| `fix384-gcp-redis-client-r1` / 로컬→GCP B | 최초 제어기로 Redis 중지·재시작 | 초기 LIVE 후 180초 내 재연결 0회, 새 메시지 0회, exit 1 | **FAIL**. STOMP `ERROR`를 영구 중단한 결함을 발견. |
| `fix384-gcp-redis-client-r2` / 로컬→GCP B | 수정 제어기로 같은 8초 중지·재시작 | HTTP 재확인 200, LIVE 2회, 새 메시지 1회, 복구 6,942ms, exit 0 | **PASS**. Redis 장애 후 재구독과 snapshot 회복 확인. |
| `fix384-final-unit-r1` / 로컬 | `node --experimental-strip-types --test tests/dashboard-socket-reconnect.test.ts` | 9/9 통과 | 결정적 재연결·인증 경계 회귀 시험 통과. |
| `fix384-final-suite-r1` / 로컬 | `npm test` | 빌드 성공, 프런트엔드 48/48 통과 | 기존 프런트엔드 회귀 시험과 동시 통과. |
| `fix384-final-lint-r1` / 로컬 | `npm run lint` | 종료 코드 0 | 정적 스타일 검사 통과. |
| `fix384-final-type-r1` / 로컬 | 변경 소스만 `npx tsc --noEmit --skipLibCheck --strict ...` | 종료 코드 0 | 변경된 연결 제어기·훅의 strict 타입 검사 통과. |
| `fix384-typecheck-r1` / 로컬 | 전체 `npx tsc --noEmit` | 종료 코드 2, 기존 Cloudflare 타입 및 테스트 `.ts` import 관련 12개 오류 | **전체 타입 검사 미통과**. 변경 소스의 개별 검사와 구분한다. |

```text
그림 4 — Redis 단절에서 드러난 실패와 수정 검증

15:49~15:52  최초 구현: LIVE → Redis 중지 → STOMP ERROR
                           → POLLING에서 정지 → 180초 후 FAIL

15:53~15:54  수정 구현: LIVE → Redis 8초 중지
                           → POLLING → STOMP ERROR
                           → HTTP 관리자 재확인 200
                           → 새 CONNECT/SUBSCRIBE
                           → snapshot 200 → 새 MESSAGE → PASS
                                  ▲
                              6,942ms
```

Redis 관측 로그에서 490개 MESSAGE를 합산한 것만으로 장애 중 전송 지속을 주장하지 않는다. 5개 구독은 장애 직후 모두 종료됐으며, 실제 복구는 별도의 프런트엔드 클라이언트 시험에서 판정했다.

## 안전·정리·한계

- 원본 이벤트에는 시각·상태·건수·HTTP 상태만 기록했다. 토큰, 암호, 내부 IP, VM 이름, 프로젝트 ID는 수집 전에 제외했고 공개 파일을 다시 검사했다.
- B의 시험용 JVM과 두 시험 계정을 종료·삭제했고, Redis는 active로 복구했다. 요청에 따라 7대 시험 VM 자체는 계속 실행 상태로 남겼다.
- Redis 단절 후 6,942ms는 한 번의 종단 간 관측치다. 반복 분포, p95/p99, 동시 브라우저 수 증가, 실제 사용자 세션 만료, 프록시·DB 리더 장애는 이 시험에서 측정하지 않았다.
- `CONNECTED` 직후 HTTP 요약 재조회가 성공해야 놓친 상태를 보정할 수 있다. HTTP가 실패하면 기존 30초 폴링과 다음 재연결 시 재조회에 의존한다.
- 전체 `tsc`는 저장소의 선행 타입 설정 문제로 실패했다. 이를 이번 수정이 해결했다고 주장하지 않는다.

## 다음 결정

이 PR은 대시보드 프런트엔드 복구 범위로 머지 판단한다. 장시간 안정성이나 2개 백엔드에 걸친 다중 브라우저 분포를 수치로 주장하려면 별도 반복 부하 시험을 수행한다.
