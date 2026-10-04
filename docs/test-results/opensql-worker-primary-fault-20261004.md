# OpenSQL primary 프로세스 장애 중 다중 문서 Worker 복구 실측

## 결론과 범위

실제 GCP OpenSQL 3노드에서 PDF 12건과 DOCX 12건을 장애 **전에** 접수하고, 시험 전용 Worker가 이 실행의 `PROCESSING` Job을 실제 처리할 때 primary PostgreSQL postmaster를 한 번 종료했다. DB는 같은 노드에서 재시작했고, Worker lease 만료·재시도를 거쳐 **24/24건 `INDEXED`, 청크 36·활성 임베딩 36, 청크·임베딩·Outbox idempotency key 중복 0건**이었다. 장애와 겹친 첫 Attempt가 `WORKER_LEASE_EXPIRED`로 종료되고 두 번째 Attempt가 성공했다.

이것은 PostgreSQL **프로세스 장애** 시험이다. 리더 VM 상실이나 새 리더 선출, 네트워크 분할, HTTP 쓰기 무중단 보장으로 확대하면 안 된다. 첫 장애 실행에서는 업로드 중 HTTP 500도 1건 관측했다. 자동 쓰기 재시도는 추가하지 않았다.

```text
그림 1 — 실제 실행 경로

GCP 내부 부하 VM ── 24개 문서 업로드 ──▶ 내부 LB ──▶ 앱 A/B
       │                                              │
       │ PDF 12, DOCX 12                              ├─ GCS 원본 저장
       │                                              └─ OpenSQL에 문서·Job 접수
       │
       └─ 실제 BGE-M3 서버 ◀── 시험 전용 Worker(앱 A와 같은 VM)
                                        │
                                        ├─ GCS 원본 읽기·PDF/DOCX 파싱
                                        ├─ OpenSQL primary에서 Claim·완료
                                        └─ BGE-M3 벡터 → OpenSQL 청크·임베딩

OpenSQL: primary 1 + streaming replica 2 / etcd 3 / OpenProxy 2
평소 앱 A/B의 Worker 설정은 바꾸지 않고 별도 JVM만 잠시 사용했다.
```

```text
그림 2 — 장애와 복구의 내부 상태 전이

문서 24건 접수 완료 ──▶ Job PENDING 24건
                          │ Worker가 첫 Job Claim
                          ▼
                    Job PROCESSING 1건
                          │ 가드가 이 실행 ID의 PROCESSING을 확인
                          ▼
             primary postmaster SIGKILL (Patroni·etcd 유지)
                          │
             DB 연결 EOF → 실패 기록도 그 순간 DB에 쓰지 못함
                          │
             같은 노드 PostgreSQL 재시작
                          ├─ 나머지 Job 23건은 정상 처리
                          └─ 첫 Job은 PROCESSING lease 대기
                                       │ 5분 lease 만료
                                       ▼
                       Attempt 1 FAILED/WORKER_LEASE_EXPIRED
                       Job PENDING, retry_count=1
                                       │ 재시도 지연
                                       ▼
                       Attempt 2 SUCCESS → 문서 INDEXED
```

```text
그림 3 — 결과를 어떻게 판정했는가

클라이언트 접수 로그: PDF 12 + DOCX 12, HTTP 201 24건
                 │ 실행 ID가 들어간 합성 제목으로 1:1 대조
                 ▼
primary DB: 문서·버전·Job 모두 INDEXED 24건
                 ├─ 청크 36 = 활성 임베딩 36
                 ├─ 청크 자연키 중복 0
                 ├─ 임베딩 자연키 중복 0
                 ├─ Outbox idempotency key 중복 0 (전체 DB)
                 └─ 1건의 두 Attempt: FAILED → SUCCESS

이 판정은 최종 상태·중복에 관한 것이다.
Outbox 이벤트의 외부 전달 완료나 GCS 원본 해시의 독립 재조회는 포함하지 않는다.
```

## 시험 환경과 안전 관문

| 구성 | 실제 시험 환경 | 이번 시험에서 변경한 범위 |
| --- | --- | --- |
| OpenSQL·Patroni·etcd | DB VM 3대, primary 1·streaming replica 2, etcd 3/3 | primary의 PostgreSQL 프로세스만 SIGKILL; VM·Patroni·etcd는 유지 |
| OpenProxy | 기존 2대 경유 | 설정 변경 없음 |
| 백엔드 | VM 2대, 내부 LB `HEALTHY` 2/2 | 기존 앱 서비스 유지, A와 같은 VM에 시험 전용 Worker JVM만 일시 기동 |
| 캐시 | 기존 공용 캐시 VM 1대 | 변경 없음 |
| 부하·임베딩 | 기존 내부 부하 VM 1대 | 시험 전용 실제 BGE-M3 서비스 일시 기동; 추가 VM 생성 없음 |
| 저장소 | 기존 GCS 객체 저장소 | 실제 합성 PDF·DOCX 업로드·Worker 읽기 |

장애 직전 primary 1대·replica 2대의 동일 timeline 13과 복제 수신·재생 상태, etcd 3/3, BGE-M3 준비 HTTP 200, 앱 LB 2/2를 확인했다. 장애 뒤에도 같은 노드 primary 1대·replica 2대 streaming, etcd 3/3, LB 2/2로 복귀했다. 이미 준비된 복구 스냅샷을 확인했으며 디스크·VM 삭제 시험은 하지 않았다. VM 중단용 독립 Workflows 가드는 이 **프로세스만 종료하는 시험에서는 발동하지 않았다**.

## 실행별 결과

| 실행 ID | 목적·방법 | 핵심 수치 | 판정 |
| --- | --- | --- | --- |
| [`ha424work001`](evidence/issue-424/ha424work001-20261004.md) | 기존 배포 Worker 정상 기준선 | 업로드 12/12, 인덱싱 `FAILED` 12/12, Batch HTTP 422 | 실패; 장애 주입 중단 |
| [`ha424shape001`](evidence/issue-424/ha424shape001-20261004.md) | JSON 형식 진단 | PDF·DOCX 각 1, `model_attributes_type` 2건 | 배포 버전 불일치 가능성 확인 |
| [`ha424base002`](evidence/issue-424/ha424base002-20261004.md) | 최신 코드 JAR 정상 기준선 | 2/2 `INDEXED`, 청크·임베딩 3:3 | 통과; 장애 시험 허용 |
| [`ha424fault002`](evidence/issue-424/ha424fault002-20261004.md) | 업로드 중 첫 프로세스 장애 | PDF 접수 8건 `INDEXED` 8건, 재시도 1건, 아홉 번째 업로드 HTTP 500 1건 | Worker 복구 통과; HTTP 실패 별도 기록 |
| [`ha424fault003`](evidence/issue-424/ha424fault003-20261004.md) | PDF·DOCX 24건 선접수 후 두 번째 프로세스 장애 | 24/24 `INDEXED`, 청크·벡터 36:36, 재시도 1건, 중복 0 | 통과 |
| [로컬 회귀](evidence/issue-424/ha424-local-regression-20261004.md) | Python·Java·셸 검사 | Python 104건, Java 14건, 셸 문법 오류 0 | 통과 |

시험 전 준비 실패와 수정은 [준비 로그](evidence/issue-424/ha424-preparation-20261004.md)에 분리했다. `ha424fault002`의 장애→최종 Worker 완료 관측은 약 **5분 28초**, `ha424fault003`은 약 **5분 43초**다. 기본 lease 5분과 재시도 지연이 포함되며 DB 리더 선출 시간이나 HTTP 쓰기 RTO로 사용하면 안 된다.

## 발견·조치·한계

1. 기존 GCP 배포 JAR의 Batch 요청은 실제 FastAPI에서 JSON 객체로 해석되지 않아 처음 12건이 실패했다. 저장소에는 이미 JSON `Content-Type`을 고정한 수정 커밋 `32ccc58`이 있으므로, 새 제품 코드를 추가하지 않고 현재 소스에서 시험 전용 JAR를 빌드해 **시험 Worker만** 교체했다. 기존 앱 A/B JAR는 이번 시험에서 교체하지 않았으며, Worker를 상시 활성화하기 전 두 앱의 배포 버전 일치 확인이 필요하다.
2. primary 프로세스가 죽는 순간에는 Worker의 실패 보고 트랜잭션까지 DB 연결 오류를 받았다. 즉시 재처리된 것이 아니라 `PROCESSING` lease 만료 뒤 안전하게 회수·재시도됐다. 5분대 복구 시간은 기능적 내구성 통과와 별개로 사용자 체감 지연이다.
3. 첫 장애 실행 중 HTTP 500이 1건 발생했다. 이미 접수된 8건의 누락·중복은 0이지만, 실패 응답 요청은 접수 성공에 넣지 않는다. HTTP 500의 예외 단계·멱등 재시도 정책은 별도 조사 범위이며 여기서 자동 재시도를 만들지 않았다.
4. 합성 문서는 실제 PDF·DOCX 포맷이지만 각 1KB 안팎으로 작다. 이 결과는 다중 문서 장애 복구 증거이지 대형 문서 처리량이나 지속 부하 성능 수치가 아니다.
5. Outbox idempotency key 중복은 0이지만 이벤트는 `PENDING`; dispatcher의 전달·소비·재처리까지 성공했다고 주장하지 않는다. GCS 객체 해시도 이 실행에서 독립적으로 재조회하지 않았다.

## 원복

시험 전용 Worker JVM과 BGE-M3 서비스를 종료했다. 기존 앱 A/B, DB 3대, 공용 캐시, 내부 부하 VM은 계속 실행한다. 여섯 VM에 개별 등록했던 시험용 임시 SSH 키는 각 VM의 메타데이터가 정확히 이 키만 가진 것을 확인하고 제거했으며, 제거 후 모두 `ssh-keys` 항목이 없음을 확인했다. 로컬 임시 개인키·공개키·known-hosts 사본도 삭제했다. Worker 임시 systemd unit은 SIGTERM에 따른 `failed` 표시가 남을 수 있으나 프로세스는 종료됐고, 키를 제거한 뒤에는 재접속해 `reset-failed`를 실행하지 않았다. 비밀·내부 주소·프로젝트 식별자는 이 보고서와 각 로그에 저장하지 않았다.
