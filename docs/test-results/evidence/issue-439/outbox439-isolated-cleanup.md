# 격리 OpenSQL Dispatcher 시험 종료·원복 확인

- 확인 시각: 2026-10-06 **00:58 KST 이후**
- 목적: 시험 전용 DB·임시 연결을 제거하고 원래 Outbox·Job이 보존됐는지 확인
- 위치: 로컬 읽기 전용 JDBC/Cloud SDK 및 현재 primary 컨테이너의 관리 명령
- 성공 기준: 시험 DB 부재, 원래 `PENDING` Event **53건**·그 출처를 참조하는 Job **53건**, 터널·임시 키·VM 메타데이터 키 부재

| 명령 또는 방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| `pg_database`·`pg_stat_activity` 읽기 전용 조회 | 이번에 만든 격리 DB **1개**, 크기 약 **11MB**, 열린 연결 **0건**, 현재 primary 확인 | 삭제 범위가 시험 DB 하나임을 확인 |
| 현재 primary에서 해당 격리 DB만 `DROP DATABASE` | `DROP DATABASE` 성공 | 이번 실행에서 생성한 시험 Event·Job·시드 데이터 제거. 기존 `docgrid` DB는 대상이 아님 |
| 원래 DB에 읽기 전용 JDBC 재접속 | 격리 DB **0개**, 원래 `PENDING` Event **53건**, 그 Event를 참조하는 Job **53건**, 현재 primary 확인 | 기존 백로그·Job 관계 보존 |
| SSH ControlMaster 정상 종료 및 로컬 포트 확인 | 터널 종료 응답, 루프백 시험 포트 LISTEN **0개** | 임시 JDBC 경로 종료 |
| 단일 DB VM 메타데이터 조회 | 인스턴스 `ssh-keys` 항목 없음 | 앞서 시도한 임시 공개키 등록은 GCP 내부 오류 2회로 적용되지 않았으며 잔여 키 없음 |
| 정확한 시험 임시 디렉터리의 키·조회용 Java 소스·known_hosts 삭제 | 해당 임시 파일과 디렉터리 제거 | 시험용 개인키·조회 파일 잔여 없음 |

주의: 기존 승인된 SSH 키는 변경하거나 삭제하지 않았다. 앱 A/B·공용 캐시 VM은 기동하지 않았고, 원래 DB Event 53건을 삭제·완료 처리하지 않았다. 제거한 것은 **이번 실행에서 새로 만든 약 11MB 격리 DB와 시험 임시 파일**이며, 격리 DB 행 자체는 복구 불가능하다. 결과 수치와 재현용 테스트 소스는 별도 비식별 실행 기록에 남겼다.

이후 전용 Gradle 태스크의 실동작을 [`outbox439-isolated-07`](outbox439-isolated-07.md)에서 **새 격리 DB를 재생성**해 한 번 더 검증했다. 그 DB도 동일하게 연결 0건을 확인한 뒤 제거했으며 최종 집계는 **격리 DB 0개, 원래 Event/Job 53/53, 임시 터널 포트 0개**였다.

자동 `@Scheduled` 주기 확인을 위해 [`outbox439-isolated-08`](outbox439-isolated-08.md)에서 또 다른 격리 DB를 재생성했다. **Event 3건 모두 완료, 성공 Attempt 3건, 의도한 실패 Attempt 1건, 출처 Job 2건**을 확인했다. 삭제 직전 DB는 약 **11MB**, 활성 연결 **0개**였으며 해당 DB만 `DROP DATABASE`했다. 재조회에서 시험 DB **0개**, 원래 DB의 Event/Job **53/53**을 확인했다. SSH ControlMaster와 이번 실행의 `known_hosts` 임시 파일도 제거했다. 기존 SSH 키는 변경하지 않았다.
