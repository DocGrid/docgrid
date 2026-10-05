# 기존 Outbox 53건 삭제·소비 전 외래키 안전 관문

- 실행 ID: `outbox439-gcp-05`
- 실행 시각: 2026-10-05 23:59–2026-10-06 00:06 KST
- 목적: 사용자가 불필요하다고 지정한 대기 이벤트 53건을 삭제해도 연결된 Job·이력에 영향을 주지 않는지 확인
- 위치: 로컬 일시 실행 Java 17/pgJDBC 클라이언트 → 루프백 전용 SSH 터널 → 승인된 DB VM 한 대 → 현재 OpenSQL primary
- 성공 기준: 같은 세션에서 primary·읽기 전용 확인, 대상 건수와 실제 외래키 참조 건수 집계, 임시 접속 완전 회수
- 결과: **참조 확인 성공, 이벤트 삭제·Dispatcher 기동은 중단**. 53건 전부 실제 Embedding Job이 참조하므로 Outbox 행만 삭제할 수 없음

| 실행 위치·명령 또는 방법 | 목적 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| GCP VM 상태, `gcloud compute instances list` | 안전 관문 전 앱·DB 상태 확인 | DB VM **3/3 실행**, 앱 **0/2**, 공용 캐시 **0/1** 실행 | 앱·Dispatcher는 이 실행에서 기동하지 않음 |
| 승인된 DB VM 경유 Patroni `/primary` | SQL 대상 노드 재확인 | 한 노드만 HTTP **200**, 나머지 두 노드는 **503** | HTTP 200 노드로 임시 터널 연결 |
| 로컬 JDBC의 같은 세션에서 `pg_is_in_recovery()`와 `transaction_read_only` 조회 | 역할 변경·오접속 상태에서 집계하거나 변경하지 않도록 검증 | `primary_confirmed=true`, `read_only_transaction=true` | 이하 집계는 현재 primary의 읽기 전용 트랜잭션에서 실행 |
| `sync_outbox_events` 건수와 관련 테이블 `EXISTS` 집계 | 삭제 대상과 실제 외래키 참조 확인 | 전체 **53건**, `DOCUMENT_VERSION_CREATED / PENDING` **53건**, Embedding Job 참조 **53건**, 정합성 이슈 참조 **0건**, Delivery Attempt 참조 **0건** | 이벤트만 `DELETE`하면 Job 외래키가 막는다. 연결을 제거하려면 Job의 출처를 변형해야 하므로 원요청보다 영향 범위가 커짐 |
| 연결된 Job 상태별 집계 | 이벤트 소비 또는 삭제의 실제 영향 추정 | `INDEXED` **34건**, `FAILED` **19건** | 53건이 모두 무의미한 빈 행은 아님. 실제 Job 상태를 연결하는 원장임 |
| `pg_constraint`의 실제 FK 목록 | 저장소 마이그레이션과 실행 DB 제약 일치 확인 | `embedding_jobs`, `sync_consistency_issues`, `sync_event_delivery_attempts`의 참조 확인 | 앞의 두 참조는 이벤트 단독 삭제를 막을 수 있고, Attempt는 삭제 시 연쇄 제거됨 |
| VM 메타데이터·로컬 포트·임시 파일 재조회 | 임시 접근 회수 | SSH 터널 리스너 부재, 인스턴스 임시 `ssh-keys` 부재, 로컬 키·조회용 소스 부재 | 임시 수단 제거 완료 |

집계는 이벤트 ID·payload·문서 내용·사용자 정보·내부 주소를 **SELECT하거나 기록하지 않았다**. 가장 오래된 `occurred_at`은 **2026-10-02 09:13:11.256192**, 가장 최근은 **2026-10-03 23:36:31.94908**이다. 두 컬럼은 시간대 없는 DB `TIMESTAMP`이므로 UTC/KST 절대 시각으로 단정하지 않는다.

판정: 현재 [문서 버전 Handler](../../../../backend/src/main/java/com/opensource/docgrid/domain/sync/service/handler/DocumentVersionSyncEventHandler.java)는 `source_event_id` Job이 있으면 관계를 검증하고 신규 Job 생성 없이 반환한다. 그러나 사용자의 “53건 삭제”를 실행하려면 현재 참조 중인 53개 Job의 `source_event_id`를 비우거나 Job까지 삭제하는 별도 변경이 필요하다. 이는 멱등성 연결 또는 실제 Job·인덱싱 이력을 훼손할 수 있다. 따라서 **DB `DELETE`·`UPDATE` 0건, Dispatcher 소비 0건, 앱 VM 기동 0건**으로 멈췄다. 이벤트를 보존한 채 대기열에서 제외하는 별도 종결 상태나 격리 DB 시험은 원 요청과 의미·범위가 달라 사용자 선택이 필요하다.
