# 업로드 성공 응답의 손실·문서 기능 재개 계측

관련 이슈: #560 · 판정 계약: [#561](https://github.com/DocGrid/docgrid/pull/561) · [실행 절차](procedure.md) · [준비·재시도](preparation-findings.md) · [수치 원장](metrics.json)

## 목적

**격리 복원 절차는 PASS지만, 성공 응답 65건 중 복원된 업로드는 33건이고 DB 미복원은 32건이다.** 기능 코호트 5건은 모두 복원됐고 문서 기능 6종·최종 정합성·원복 관문은 통과했다. 절차의 PASS를 데이터 무손실이나 운영 백업 완료로 해석하지 않는다.

HTTP 업로드 성공을 받은 문서가 복원 DB의 관계와 GCS 원본 바이트까지 함께 보존됐는지 확인한다. 최초 상태를 Worker·Flyway·수선 전에 감사하고, 후속 인덱싱으로 최초 손실을 덮어쓰지 않는다.

| 측정 대상 | 실제 결과 |
|---|---:|
| 장애 전 성공 응답 | 65건 = 기능 5건 + 손실 관측 60건 |
| 최초 `RESTORED` | 33건 = 기능 5건 + 손실 관측 28건 |
| 최초 `DB_MISSING` | 32건, 전부 손실 관측 코호트 |
| 결과 불명 / HTTP 오류 | 각각 0건 |
| DB 미복원 32건 중 원본만 보존됨 | 27건. 원본이 있어도 DB 관계가 없어 업로드 복원으로 세지 않음 |
| 부분 DB / 원본 누락 / 해시 불일치 | 각각 0건. DB가 미복원된 요청의 원본은 이 분류와 별도로 위에서 집계 |
| 연속 복구 성공 응답 경계와 T0 차이 | **412.061초**. 중간 누락 뒤 다시 복원된 성공 응답 0건 |
| 문서 기능 확인 | 6/6 통과 |
| 최종 감사 대상 | 복원 문서 33건 + 새 문서 1건 = 34건 |
| 미완료 Job / 중복 Chunk / 중복 Embedding / 누락 Embedding | 각각 0건 |

이는 **한 회차의 관측 손실 구간**이며 운영 RPO 보장이 아니다. 결과 불명 요청은 이번 실제 회차에서 발생하지 않았고, 계약 시험에서만 다뤘다.

## 테스트 환경

| 구성요소 | 실제 사용·역할 | 경계 |
|---|---|---|
| 제품 코드 | `3bff887a5f6abdf4155957a658599f8f0366a141`, Java 17 JAR. SHA-256 `08c5c458110bc96fc4c92cc5b02bd160f6c08b59e21d9ef536f2fa50aa76b27d` | Java 제품 코드 변경 없음 |
| OpenSQL | 실제 설치된 PostgreSQL 17.8 기반 OpenSQL, vector 0.8.1, 기존 3노드의 전용 논리 DB | 물리 기준 백업은 클러스터 단위. 감사·HTTP는 시험 DB만 사용 |
| 복원 대상 | 기존 비동기 Standby 호스트를 직렬 재사용, 별도 PGDATA·소켓·포트 | 원래 DB와 동시 실행하지 않음. 리더·동기 Standby 유지. 1,800초 자동 원복 장치 |
| 앱·캐시 | 로컬 전용 JVM·Valkey. 복원 뒤 Worker 동시 처리 상한 2 | 공개 앱 설정·개인 환경 파일 변경 없음 |
| 임베딩 | 실제 BGE-M3, 1,024차원. 기존 모델 컨테이너 재사용 | 제어 프록시는 실제 응답의 대기만 제어. 가짜 벡터 없음 |
| GCS | source·preserved·database의 시험 소유 버킷 3개 | 공개 원본 버킷과 분리, generation·SHA-256·크기 고정 |
| 백업 도구 | Barman 3.19.1, PostgreSQL 클라이언트 17.8, `file`·`rsync` | 별도 PostgreSQL 서버로 대체하지 않음. 영구 replication slot 추가 없음 |
| 복원 도구 | 네트워크 없는 새 도구 컨테이너·빈 카탈로그 | 동결 입력만 마운트. 원본 카탈로그·ADC 마운트 없음 |

계측은 `DR-RESTORE-20261018-FORMAL1` 한 회차다. 준비 단계는 2026-10-18 12:32 UTC 이후, 요청 구간은 12:49–13:01 UTC다. 한국 시각은 각각 21:32 이후·21:49–22:01이다. 모든 원장에는 시간대가 포함된다. 이 환경은 전용 성능 벤치마크 장비가 아니다.

## 테스트 방법

```text
그림 1 — 성공 응답부터 외부 입력까지 [실행]

시험 클라이언트
  ├─ 요청 전: request_id·Idempotency-Key·원본 SHA·크기 기록
  └─ POST /api/documents
       → DocumentUploadFacade: 원본 GCS 저장·후보 검증
       → DocumentUploadService: DB Transaction
            Document + Version + FileObject 관계
            Outbox Event + EmbeddingJob + 요청 키의 완료 응답
       ← HTTP 201 + 문서·버전·파일·Job ID 4종
  └─ 응답 직후: DB 밖 해시 연결 원장에 ACK 기록

시험 DB ── 실제 스트리밍 수신 ── Barman 기준 백업·완성 WAL·identity
원본 GCS ── 실제 바이트·generation 확인 ── 원본 보존 버킷
                                │
                300초 예약 전송 두 번 + 준비 전송 한 번
                                ▼
                  T0 뒤 추가 취득하지 않는 고정 입력
```

업로드는 [원본 저장](https://github.com/DocGrid/docgrid/blob/3bff887a5f6abdf4155957a658599f8f0366a141/backend/src/main/java/com/opensource/docgrid/domain/document/service/DocumentUploadFacade.java#L90-L127)과 [DB 원장·Job·응답 기록](https://github.com/DocGrid/docgrid/blob/3bff887a5f6abdf4155957a658599f8f0366a141/backend/src/main/java/com/opensource/docgrid/domain/document/service/command/DocumentUploadService.java#L101-L160)을 거친다. DB 백업과 객체 사본의 진도가 같다고 가정하지 않고 성공 응답별로 대조한다.

완료 문서 4건과 실제 모델 응답 대기 중 JVM을 종료한 Job 1건을 먼저 준비했다. 중단 직전에는 `PROCESSING`, 문서 `INDEXING`, 버전 `EMBEDDING`, Attempt 1개·Chunk 3개·Embedding 0개였다. 이후 Worker를 끄고 기준 백업을 만들었다. 손실 관측 60건은 목표 11초 간격이며 전송 동안 업로드가 정지하므로 실제 간격은 원장을 기준으로 한다.

| 외부 전송 | 완료 UTC | 기준 백업 크기 | 완성 WAL + history 누계 | 보존 원본 누계 | 실제 전송 시간 |
|---|---|---:|---:|---:|---:|
| 준비 | 12:44:55.930 | 142,940,160바이트 | 4개 | 5건 | 59.888초 |
| 첫 예약 구간 | 12:55:35.364 | 같은 백업 | 7개 | 33건 | 73.514초 |
| 둘째 예약 구간 | 13:00:22.660 | 같은 백업 | 8개 = WAL 7개 + history 1개 | 60건 | 63.774초 |

예약 시작 지연은 각각 5.005초·2.033초였다. 전송 경계에서는 `pg_switch_wal()`을 실행하고 완성된 파일만 보존했다. 전송 주기 300초만으로 RPO 300초를 보장하는 구성은 아니다.

```text
그림 2 — 최초 감사가 활성화를 통제하는 내부 순서 [실행]

T0 13:01:13.892692 UTC: 시험 API 중지 시작
  → API·수집기·원본 DB 터널 종료, 리스너 2개 닫힘
  → 원본 GCS 드라이버 접근 거부 확인
  → cutoff 13:01:24.539 UTC: 입력 고정 (T0 +10.646초)
      │
      ▼
외부 generation 다운로드 → SHA·크기 대조 → 빈 카탈로그 import·verify
  → 새 PGDATA → 같은 라이선스 호스트에서 실제 OpenSQL 기동
      │
      ▼
snapshot: 복원 소켓·data_directory 확인 + 읽기 전용 SQL
  → initial_upload_requests를 user_id + request_key로 조회
  → 응답 ID 4종·관계 + 동결 보존 객체의 바이트 대조
      │
      ▼
13:03:28.438 UTC: 최초 감사 단독 저장·fsync
  ├─ 기능 코호트 5/5 RESTORED, DB_PARTIAL/객체/해시 오류 0
  └─ DB_MISSING 32건을 그대로 보존 → 활성화 허용
      │
      ▼
복원 DB만 보존 버킷으로 재지정 → Flyway·Worker 기동 → 복원 계정 로그인
  → 실제 HTTP 기능 검사 → 최종 감사 별도 저장 → 무조건 원복·정리
```

원본 버킷 IAM 차단이나 원본 사이트 전체 소실을 재현하지 않았다. 원본 데이터 보충은 드라이버와 복원 컨테이너 경계로 막았고, 기존 호스트의 역할 확인·원복 제어는 유지했다. 복원 서버는 원본 연결 정보를 비우고 동결한 마지막 WAL까지 재생했다.

## 관측 결과

| 기능·절차 | 표본·결과 | cutoff 이후 | T0 이후 |
|---|---|---:|---:|
| 기존 다운로드 | 4건 HTTP 200·크기·SHA 일치 | 176.992초 | **187.638초** |
| 기존 검색 | 사전 고정 합성 Chunk 질의·기대 문서 Top 20 포함 1/1 | 192.841초 | **203.487초** |
| 중단 Job 재개 | 같은 Job `INDEXED`, Job 1개·Attempt 2개 | 238.483초 | **249.130초** |
| 새 업로드 | HTTP 201, 1/1 | 246.122초 | **256.769초** |
| 새 인덱싱 | `INDEXED`, 1/1 | 571.408초 | **582.054초** |
| 새 다운로드 | HTTP 200·원본 SHA 일치, 1/1 | 573.186초 | **583.832초** |

복원 연산자 자체는 100.672초다(13:01:24.555–13:03:05.227 UTC). 원격 호스트 준비·기동을 포함하며 전체 기능 복구 시간과 다르다. 각 기능은 순차적으로 관측했으므로 **확인 시각**이며 실제로 처음 가능해진 최소 시각을 추정하지 않는다. 새 인덱싱 시간에는 복원된 대기 Job 처리 시간이 포함된다.

```text
그림 3 — DB·GCS 시점 차이와 사용자 기능 [관측]

65건의 ACK 순서
  ┌─────────────────────┬──────────────────────┐
  │ 앞 33건: RESTORED    │ 뒤 32건: DB_MISSING  │
  │ 기능 5 + 손실 28     │ 모두 손실 코호트     │
  └─────────────────────┴──────────────────────┘
  마지막 복원 ACK 12:54:21.832 UTC
           └─ T0까지 412.061초, 중간 누락 뒤 복원된 ACK 0건

원본 보존은 60건
  ├─ DB도 복원: 33건 → 바이트·관계 대조 통과
  └─ 원본만 존재: 27건 → 문서 복원으로 계산하지 않음
     나머지 5건은 이번 동결 원본에도 포함되지 않음

복원 뒤
  기존 다운로드 T0+187.638초 → 검색 +203.487초 → Job 재개 +249.130초
  → 새 업로드 +256.769초 → INDEXED +582.054초 → 원본 대조 +583.832초
  → 최종 34문서: 미완료/중복/누락 각 0 → 원래 클러스터 복귀
```

### 결과 아티팩트

| 실행·목적 | 위치·방법 | 판정·근거 |
|---|---|---|
| 본시험 준비 | 전용 DB·실제 GCS/BGE/Barman, 9단계 | [준비 결과](runs/DR-RESTORE-20261018-FORMAL1/preparation-result.json), Barman 24/24·백업 DONE·verify 0 |
| 본시험 | 격리 OpenSQL·인증 HTTP, 성공 응답 65건 | [요약](runs/DR-RESTORE-20261018-FORMAL1/summary.json), [이벤트](runs/DR-RESTORE-20261018-FORMAL1/events.jsonl), [동결 입력](runs/DR-RESTORE-20261018-FORMAL1/inputs.json) |
| 최초 감사 | Worker·Flyway·수선 전, 요청 키·ID·원본 대조 | [최초 스냅샷](runs/DR-RESTORE-20261018-FORMAL1/initial-snapshot.json), [최초 감사](runs/DR-RESTORE-20261018-FORMAL1/initial-audit.json) |
| 기능·최종 상태 | 실제 BGE·다운로드·검색·신규 작업 | [기능 시각](runs/DR-RESTORE-20261018-FORMAL1/functional-observations.json), [최종 스냅샷](runs/DR-RESTORE-20261018-FORMAL1/final-snapshot.json), [최종 감사](runs/DR-RESTORE-20261018-FORMAL1/final-audit.json) |
| WAL 경계 추가 조회 | 복원 DB 읽기 전용 SQL·파일명 기반 끝 LSN 계산 | [관측](checks/wal-boundary-r1/result.json), [분석](checks/wal-boundary-r1/analysis.json) |
| 원복·정리 | 원래 프로세스 재개·역할·설정 해시·소유 자원 확인 | [정리 기록](runs/DR-RESTORE-20261018-FORMAL1/runtime-cleanup-observations.json) |
| 계약 회귀 | 로컬 Python, 실제 Java enum + 합성 감사/제어 프로세스 | [40/40 통과 로그](checks/contract-r5/events.log). 실제 클라우드 실패 입력 40종을 재현했다는 뜻은 아님 |

개별 준비 단계와 전송 회차의 spec·result는 비공개 작업 보관소에서 실행별로 분리했다. 저장소에는 최초·최종 감사, 필수 준비 결과, 불완전 회차, 정리 등 필요한 증거 36개만 공개했다. 이전 리허설은 기동 단계에서 `INCOMPLETE`, 후속 기동 진단은 각각 `INCOMPLETE`·진단 `PASS`이며 본시험에 합산하지 않는다. [준비·재시도 상세](preparation-findings.md)를 함께 읽어야 한다.

## 검증 과정에서 확인된 사항

1. **외부 전송 성공과 데이터 최신성은 다르다.** 마지막 입력에는 WAL 7개·history 1개와 원본 60건이 있었지만 실제 DB 복구는 ACK 33건까지였다. 복원 DB의 마지막 재생 LSN `0/25000000`은 동결한 마지막 WAL 끝과 일치한다. 마지막 재생 트랜잭션은 12:54:21.655964 UTC다. 고정 입력을 복원 중 빠뜨렸다는 근거는 발견하지 못했다. 수신·archiver·전송 사이 최신성 차이의 세부 원인은 이 회차에서 분리하지 않았다. 후속 운영화에는 최신 완료 쓰기와 외부 WAL 도달 경계를 연결하는 점검이 필요하다.
2. **판정기 상태 계약을 보완했다.** 기존 공통 목록은 실제 문서 `INDEXING`·버전 `EMBEDDING` 등을 거부했다. 엔티티별 상태 목록으로 나누고 실제 Java enum 18종·잘못된 엔티티 상태 3종을 독립 입력으로 시험했다. 수정 전 4개 메서드 실패 → 수정 후 40/40 통과다. 제품 Java 코드 변경은 없다.
3. **라이선스 경로는 복원 런처의 실행 조건이었다.** 같은 호스트·같은 백업 바이트에서 기존 `OPENSQL_LICENSE_PATH`를 전달한 뒤 실제 기동이 됐다. 설치 라이선스 수정·재발급이나 제품 결함·만료 판정은 하지 않았다. 첫 리허설은 원시 stderr를 보존하지 않아 후속 진단 오류를 소급 적용하지 않았다.
4. **시간·보장의 범위가 제한된다.** 외부 전송마다 WAL 전환을 제어했고 스트리밍/수신기 한 회차만 시험했다. 장기 정기 백업·수신 중단 경보·Primary 교체 재연결, OpenProxy 경로, 공개 사용자 전환, Ollama 생성 답변, 전체 사이트 소실은 미검증이다. 이 값은 공개 서비스 RTO나 RPO 0 보장이 아니다.
5. **증거와 비공개 실행 정보는 분리했다.** 앱·SSH 원시 출력은 사전 허용 이벤트 또는 사전 마스킹으로 수집했다. 접속 argv·인증정보·내부 자원 식별자·원본 백업·라이선스는 공개하지 않는다. 공개 사본의 해시 목록은 [export-index.json](export-index.json)에 있다. 접속 연산자의 임시 구현 전체를 배포하는 원클릭 클라우드 복구 패키지는 아니다.

### 변경 구성요소

| 파일·구성요소 | 변경 목적 |
|---|---|
| `scripts/opensql/dr_recovery.py` | 문서·버전·Job의 상태 허용 목록을 실제 Java 계약별로 구분 |
| `scripts/opensql/test_dr_recovery.py` | Java enum을 독립 입력으로 읽고 엔티티별 수용·오입력 거부를 회귀 검증 |
| 이 결과 디렉터리 | 사전 계획·실제 절차·불완전 회차·최초/최종 감사·시간·정리를 함께 공개 |

시험 버킷 3개 삭제·임시 ADC 복사본 제거·개인 환경 파일 두 개의 동일성·사용자용 원본 버킷 보존은 [별도 작업 정리 결과](checks/task-cleanup-r1/result.json)에서도 확인했다. 원래 ADC는 철회하지 않았다.

공개 설정과 개인 환경 파일을 배포용으로 변경하지 않았다. 시험 GCS 객체 삭제는 활성 세대 정리이며, soft delete 보존이 남을 수 있으므로 즉시 물리 소거로 표현하지 않는다.
