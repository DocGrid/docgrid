# GCP DB 게스트 역할 확인과 Outbox 조회 한계

- 실행 ID: `outbox439-gcp-02`
- 실행 시각: 2026-10-05 21:17–21:31 KST
- 환경: Cloud Shell → 승인된 DB VM 한 대. 프로젝트·호스트·IP·사용자 식별자·SSH 공개키는 기록하지 않음
- 목적: Outbox 소비 전 DB 역할과 대기 건수를 읽기 전용으로 확인
- 성공 기준: DB 역할과 이벤트 유형·상태별 건수·가장 오래된 대기 시각 확인, 임시 접근 완전 회수
- 안전 조건: DB 쓰기, Dispatcher 기동, 시험 이벤트 생성, 다른 VM의 SSH 메타데이터 변경 없음

| 위치·방법 | 확인 이유 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| Cloud Shell, 대상 VM 한 대의 인스턴스 메타데이터 | 기존 접근 수단을 변경하지 않고 만료형 임시 키로 SSH | 해당 VM의 인스턴스 SSH 키가 기존에 없음을 확인한 뒤 키 **1개** 등록, SSH 읽기 접속 성공 | 프로젝트 공용 키·다른 VM은 변경하지 않음 |
| 대상 VM, 프로세스 목록 | 실제 Patroni/PostgreSQL 실행 여부 | Patroni와 PostgreSQL 프로세스 확인 | VM 실행 표시보다 좁은 실제 서비스 실행 증거. DB SQL 성공 증거는 아님 |
| 대상 VM, Patroni REST `/primary`·`/replica` | primary에서 집계해야 최신 backlog를 판정할 수 있음 | `/primary`: **HTTP 503**, `/replica`: **HTTP 200** | 조회한 VM은 replica. 이 VM의 결과를 primary 최신 상태로 취급하지 않음 |
| 대상 VM, SQL 클라이언트 탐색 | `sync_outbox_events`를 읽기 전용 집계할 방법 확인 | `psql` 없음, 기본 Python `psycopg2` 없음 | Outbox 유형·상태별 건수, 가장 오래된 대기 시각 **미측정**. 0건으로 간주하지 않음 |
| Cloud Shell 및 VM 인스턴스 메타데이터 | 임시 접근 회수 | 대상 VM `ssh-keys` 부재, Cloud Shell 개인키 부재 각각 **true** | 임시 키 제거 완료. 프로젝트 공용 메타데이터는 변경하지 않음 |

`psql` 실행 시도는 실행 파일 부재로, Python DB 클라이언트 확인은 모듈 부재로 실패했다. 인증 우회, 제품 설치, 서버 설정 수정, 비밀번호 출력은 하지 않았다. SQL이 실행되지 않았으므로 Outbox 소비 안전 관문은 **NO-GO**다. 앱 A/B·캐시 VM을 시작하지 않았고, 시험 이벤트·장애 주입은 **0건**이다.

다음 실행은 최신 primary에서 읽기 전용 SQL 접속 수단과 앱 A/B의 실제 Dispatcher 적용값을 마련한 뒤 별도 실행 ID로 기록해야 한다. 이 문서는 정상 소비·재시도·GCS 삭제가 성공했다는 증거가 아니다.
