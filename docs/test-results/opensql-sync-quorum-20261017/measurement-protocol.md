# 격리 동기 정책 비교의 판정·계측 경계

## 사전 조건

- 실제 OpenSQL PostgreSQL 17.8·Patroni 4.0.5, 원본 OpenProxy와 동일 역할 호환 어댑터.
- 지정형/Quorum 모두 strict=true, synchronous_node_count=1, 쓰기 synchronous_commit=on.
- 각 회차의 데이터·DCS 시험 namespace를 초기화하고 동일 호스트를 초기 Primary로 시작한다. Standby 두 대가 streaming인지 실제 SQL로 확인한다.
- 스키마와 Flyway 이력만 동일하게 사전 적재한다. 문서 데이터가 없는 스키마 snapshot SHA-256: `6c730ecef5fbb9587e57f325f18421b2f663ae057eb42078fb29446bdc17d219`.
- 합성 사용자·문서·READ 권한 48개를 직접 Primary에서 배치로 준비한다. 준비 시간은 장애 지연에 포함하지 않는다.
- 측정 JVM·Controller·JPA·Hikari·부하는 동일하다. 동시성 2, 표본 48개, 요청마다 독립 UUID. 모델·검색 품질·공개 로그인·GCS는 포함하지 않는다.

## 장애

- 첫 4개 응답 뒤 지정 동기 후보인 시험 node2를 강제 종료하고 약 8초 대기 후 시작한다.
- 별도 회차에서는 시험 Primary node1을 강제 종료해 **후보를 지정하지 않은 자동 승격**을 관찰한다. 과거 #3310의 후보 지정 수동 전환 경로와 구분한다.
- 실제 종료 완료·새 Primary 관측·재가입 시각은 외부 제어 원장에 KST로 기록한다.

## 통과 기준

- 최초 204를 받은 요청 키가 모두 현재 Primary에 남아야 한다.
- 결과 불명은 같은 키 WAL 재주장으로 해소하고, 최종 요청 키 48개·권한 행 0개로 수렴해야 한다.
- 응답 뒤 최종 인가에서 접근 허용 0건. 권위 조회 실패는 허용으로 집계하지 않고 별도 건수로 보존한다.
- 최종 대조도 read-write Transaction으로 현재 Primary에 고정한다. 추가 독립 JDBC 감사로 실제 외부 요청 키들을 대조한다.

## 시간 해석

- `max_initial_204_gap_seconds`: 계속 제공한 최초 요청 48개 중 연속 204 응답 사이의 최대 간격이다. 준비·의도적 재시도 대기 구간은 제외한다.
- 쓰기 재개: Standby 장애는 종료 확인 이후 첫 204, Primary 장애는 새 Primary 관측 이후 첫 204를 사용한다. 시작은 실제 종료 확인 시각이다.
- 지연 p50/p95는 최초 48개 응답 전체(불명 포함)의 MVC 호출 시간을 정렬해 nearest-rank로 계산한다.
- Hikari active/pending 최대값은 100 ms 간격 표본이다. 순간 최대를 놓칠 수 있다.
- 이 수치는 로컬 JVM→IAP→격리 OpenProxy·DB 경로이며 공개 서비스 전체 RTO·성능 SLA가 아니다. 기존 라이선스 VM의 물리 자원을 공유하므로 성능 우열을 일반화하지 않는다.

## 원본과 호환 계층

원본 OpenProxy는 native sync_standby와 quorum_standby를 모두 거부했다. 성공한 비교는 역할 어댑터 경유 결과이며, 원본 공급사 공식 동기/quorum 지원으로 표현하지 않는다.
