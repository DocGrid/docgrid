# GCP OpenSQL primary의 Outbox 읽기 전용 backlog 실측

- 실행 ID: `outbox439-gcp-04`
- 실행 시각: 2026-10-05 22:23–22:35 KST
- 환경: 로컬 일시 실행 JDBC 클라이언트 → 로컬 전용 SSH 터널 → 승인된 DB VM 한 대 → OpenSQL primary. 프로젝트·호스트·IP·계정·키 값은 기록하지 않음
- 목적: Dispatcher 기동 전에 현재 Outbox 대기량과 가장 오래된 이벤트를 primary에서 측정
- 성공 기준: 같은 DB 세션에서 primary·읽기 전용 트랜잭션 확인, 유형·상태별 집계, 임시 접근 완전 회수
- 판정: **읽기 전용 집계 성공, Dispatcher 소비 안전 관문 NO-GO**. 예상 밖의 오래된 `PENDING` 53건이 있어 앱·Dispatcher는 시작하지 않음

| 실행 위치·명령 또는 방법 | 왜 실행했나 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 로컬 Cloud SDK, 대상 VM 메타데이터 읽기 및 만료형 공개키 한 개 등록 | 기존 SSH 권한과 공용 키를 바꾸지 않고 한 DB VM에만 일시 접속 | 등록 전 `ssh-keys` 부재 확인, SSH 접속 성공. 공개키 만료는 등록 시점부터 2시간 | 접근 범위를 승인된 VM 한 대로 한정 |
| 대상 VM을 경유한 Patroni `/primary` 조회 | SQL 집계가 향할 primary 확인 | 게이트웨이 VM과 다른 후보는 HTTP **503**, 선택한 후보는 HTTP **200** | 선택 후보를 임시 터널 대상으로 사용. REST 결과만으로 SQL 세션의 역할을 단정하지 않음 |
| 로컬 `ssh -fN -L 127.0.0.1:<local-port>:<primary>:5432` | DB 포트를 외부에 공개하지 않고 primary에 연결 | 로컬 루프백에만 임시 리스너 생성 | 앱 경로/OpenProxy 경유 시험이 아니라 관리용 읽기 전용 점검 |
| 로컬 임시 Java 17 클라이언트 + pgJDBC 42.7.11, 앱 DB 암호를 기존 `.env`에서 프로세스 환경변수로만 로드 | `psql` 없이 암호값을 출력·전송·파일로 저장하지 않고 SQL 실행 | JDBC 접속 성공, `pg_is_in_recovery()=false`, `transaction_read_only=on` | primary의 읽기 전용 트랜잭션임을 **같은 세션에서** 확인 |
| 같은 세션의 `sync_outbox_events` 유형·상태별 `COUNT`, `MIN(occurred_at)`, `MIN(available_at)` | 기동 시 과거 Event가 한꺼번에 처리될 위험 판정 | `DOCUMENT_VERSION_CREATED / PENDING` **53건**. 그 외 그룹 0개. 가장 오래된 `occurred_at`·`available_at`: **2026-10-02 09:13:11.256192** | 조회 시점의 전체 Outbox 53건이 `PENDING`. 가장 오래된 이벤트가 수일 전부터 남아 있어 자동 소비 기동 **보류** |
| 로컬·GCP 접근 정리 및 재조회 | 비밀과 임시 접근 잔존 방지 | SSH 터널 리스너 부재, VM `ssh-keys` 부재, 로컬 임시 키·JDBC 소스 부재 확인 | 임시 수단 회수 완료. 기존 `.env`는 변경하지 않음 |

실행 SQL은 아래와 같다. `payload_json`, 이벤트 ID, 문서 ID, 사용자 정보, 객체 키는 **SELECT하지 않았다**. `occurred_at`·`available_at`은 DB의 시간대 없는 `TIMESTAMP` 컬럼이므로 위 값을 UTC 또는 KST 절대 시각으로 단정하지 않는다.

```sql
SELECT pg_is_in_recovery(), current_setting('transaction_read_only');
SELECT event_type, status, COUNT(*),
       MIN(occurred_at), MIN(available_at)
  FROM sync_outbox_events
 GROUP BY event_type, status
 ORDER BY event_type, status;
```

도구 준비 중 기본 JShell은 로컬 JDI 포트 생성이 막혔고, 로컬 실행 모드에서는 `java.sql.DriverManager` 로딩이 실패했다. 이 실패는 SQL 실행 전 발생했으며 제품 장애로 분류하지 않는다. 이후 파일에 암호를 넣지 않는 임시 Java 소스 실행으로 **별도 정상 조회**를 완료했다. 중간 실패를 성공 결과로 덮어쓰지 않았다.

`PENDING` 53건의 발생 이유는 이번 읽기 전용 집계만으로 확정할 수 없다. 앱 A/B의 실제 배포 버전·Dispatcher 적용값을 게스트에서 확인하지 않았고, 이벤트의 payload·개별 식별자도 조회하지 않았다. 기본 설정 `sync.dispatcher.enabled=false`와 앱 VM 중지 상태는 가능한 설명이지만, **운영 적용값의 증거는 아니다**. 오래된 backlog의 출처와 재처리 영향이 확인되기 전까지 Dispatcher 활성화·시험 이벤트 생성·장애 주입은 모두 **NO-GO**다.
