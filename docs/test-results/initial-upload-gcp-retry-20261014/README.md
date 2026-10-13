# GCP 최초 업로드 결과 불명·재시도 검증

2026-10-14 KST, `develop` 병합 커밋 `e94d53994ed7a71bdd50b5eb3fc6c3ddaac87a2c`의 백엔드 JAR(SHA-256 `22d65f06b7b9227e7edcd9357db190502d0f62a56874f5a1618eb04bf6355a6f`)을 기존 GCP 앱 A/B에 순차 배포했다. Flyway 최종 버전은 48, 두 앱의 관리 포트 readiness는 200이었다. 인덱싱 Worker와 Sync Dispatcher는 계속 비활성, 저장소는 GCS였다. 새 테스트 코드가 서비스에 배포된 것이 아니라, 병합된 Java 코드로 만든 JAR을 배포했다.

## 목적과 판정 기준

같은 사용자가 같은 `Idempotency-Key`와 동일한 PDF·메타데이터를 앱 A/B에 다시 보내도 논리 업로드가 하나인지 확인한다. 첫 HTTP 응답을 읽지 못한 경우에는 **재전송 전** DB 커밋을 확인한 다음 재전송한다. 서로 다른 파일에 같은 키를 쓰면 409여야 한다. 각 회차에서 DB의 완료 요청·파일·Job 수, GCS 원본 바이트 SHA-256, A/B 다운로드 결과를 분리해 대조한다.

시험 클라이언트는 운영자 컴퓨터의 IAP 터널을 통해 앱 A/B에 직접 접속했다. 따라서 이번 HTTP 결과는 **Vercel·공개 게이트웨이·내부 LB를 통과한 E2E 결과가 아니다.** 합성 PDF만 사용했고 실행마다 계정과 요청 키를 새로 만들었다. 파일 해시 외의 계정·토큰·요청 키·내부 주소·버킷·객체 키는 로그에 기록하지 않았다.

| 실행 ID | 목적·환경 | HTTP·장애 관측 | 영속 상태 | 판정 |
| --- | --- | --- | --- | --- |
| [`upload-retry-normal-20261014-01`](normal-01.md) | GCP A→B 정상 재전송 | 최초 201, 재전송 201, 다른 입력 409, 다운로드 200×2 | 완료 요청·파일·Job 각 1, 참조 GCS 객체 1건 해시 일치 | 통과 |
| [`uploadp14a`](proxy-a-01.md) | OpenProxy A 약 29.8초 중단 | 장애 중 첫 업로드 201, 복구 후 재전송 201, 다른 입력 409 | 완료 요청·파일·Job 각 1, 참조 GCS 객체 1건 해시 일치 | 통과. 실패 응답 복구는 관측하지 않음 |
| [`ha488upload01`](primary-preparation-01.md) | Primary postmaster 주입 첫 시도 | 하네스 대기 시간 만료, 업로드 요청 0 | DB 노드는 원래 리더 1·복제본 2로 복구 | **실패한 준비 회차**, 성과 집계에서 제외 |
| [`ha488upload02`](primary-process-01.md) | Primary postmaster 종료 뒤 업로드 | 첫 업로드 201, 재전송 201, 다른 입력 409 | 완료 요청·파일·Job 각 1, 참조 GCS 객체 1건 해시 일치 | 통과. HTTP 실패·승격은 관측하지 않음 |
| [`uploadlost01`](response-drop-01.md) | 완전한 요청 전송 직후 클라이언트 응답 읽기 포기 | 첫 HTTP 상태 불명, 재전송 전 DB 커밋 확인, 재전송 201, 다른 입력 409 | 전·후 완료 요청·파일·Job 각 1, 참조 GCS 객체 1건 해시 일치 | **결과 불명 재전송 통과** |

## 실행 경로와 해석

```text
합성 PDF·사용자·키를 회차마다 생성
    ├─ IAP 터널 → 앱 A : POST /api/documents
    │                 GCS 후보 저장 → OpenSQL 요청 원장·문서·버전·Job 커밋
    ├─ 응답 유실 회차만: 요청 본문 전송 뒤 응답을 읽지 않고 연결 종료
    │                    → 재전송 전 Primary DB에서 완료 요청·파일·Job 각 1 확인
    ├─ IAP 터널 → 앱 B : 같은 키·같은 파일 재전송
    │                 → 완료 응답 ID 재사용
    └─ 다른 파일+같은 키 409 → A/B 원본 다운로드 SHA 일치
                              → DB FileObject와 GCS 실제 바이트 별도 대조
```

OpenProxy A 중단은 호스트의 100초 독립 복구 가드를 먼저 무장하고, B가 ready인 상태에서 A 프로세스만 종료했다. Primary 시험은 VM이 아니라 PostgreSQL postmaster만 종료했고 Patroni·etcd는 유지했다. Primary 재실행 전에 Patroni 리더 1·streaming 복제본 2(지연 0), etcd 3/3 healthy를 확인했다. 첫 주입 시도는 하네스가 시간 초과되어 요청 0건임을 별도 기록했다.

**범위:** 응답 유실 회차는 클라이언트가 요청을 끝까지 전송하고 응답을 읽지 않은 실제 TCP 연결 종료다. 이때 서버 커밋은 재전송 **전에** DB에서 확인했다. OpenProxy·Primary 회차의 첫 요청은 모두 201이었다. 따라서 이 두 장애 회차만으로 500/503/시간 초과 요청의 안전한 재시도를 입증했다고 해석하지 않는다. 인덱싱 Worker가 꺼져 있어 Job 최종 `INDEXED`, 활성 청크, 검색 경로는 이번에 측정하지 않았다. GCS 저장 직후 앱 강제 종료와 Primary VM 전체 상실도 실행하지 않았다. DB가 참조하지 않는 GCS 후보 객체의 전체 목록은 조사하지 않았다.

## 재현 도구와 정리

- `scripts/opensql/verify_initial_upload_retry.py`: 합성 계정·PDF 생성, 동일 키 재전송, 충돌, 응답 유실, 다운로드 SHA 대조. 출력은 허용된 수치·상태만 포함한다.
- `scripts/opensql/verify_gcp_app_gcs_objects.py`: DB FileObject가 가리키는 GCS 객체의 실제 바이트를 해시 대조했다.
- DB 읽기 전용 SQL로 네 유효 회차 각각의 `file_objects`, `documents`, `document_versions`, `sync_outbox_events`, `embedding_jobs`, 완료된 `initial_upload_requests`가 **모두 1건**임을 최종 대조했다. 응답 유실 회차는 같은 집계를 재전송 전에도 확인했다.
- A/B의 자동 배포 원복 타이머는 동일 JAR·readiness 200 확인 뒤 해제했다. 이전 JAR 백업은 각 VM에 보존했다. 시험용 IAP 터널·임시 게이트 파일·VM 임시 스크립트는 제거했고, 사용하지 않은 부하 시험 VM은 원래 종료 상태로 되돌렸다. 앱 A/B·Redis는 실행 중이며, OpenProxy A/B는 각각 프로세스 1·ready·가드 없음, OpenSQL은 리더 1·streaming 복제본 2(지연 0)다. 합성 계정·문서·GCS 객체·대기 Job은 영속 증거로 남고 Worker가 꺼져 있어 인덱싱되지 않았다.

각 로그는 실행 당시 도구가 내보낸 비식별 단계 이벤트와 별도 DB·GCS 대조 결과를 합친 요약이다. 원본 HTTP 본문과 비밀 설정은 수집하지 않았다.
