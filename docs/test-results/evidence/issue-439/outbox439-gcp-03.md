# GCP primary 읽기 전용 Outbox 집계 접속 시도

- 실행 ID: `outbox439-gcp-03`
- 기록 시각: 2026-10-05 22:14 KST 기준 종료
- 환경: Cloud Shell → 승인된 DB VM 한 대의 일시적 SSH 터널 → Patroni가 primary라고 응답한 DB VM
- 목적: Outbox 유형·상태별 대기 건수와 가장 오래된 이벤트 시각을 읽기 전용으로 확인
- 성공 기준: primary 확인과 집계 SQL 완료, 암호·터널·임시 키 잔존 0
- 결과: **접속 준비까지 확인, 집계 SQL 미실행(NO-GO)**. 앱·Dispatcher 기동, DB 쓰기, 이벤트 생성은 0건

| 위치·방법 | 확인 이유 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| Cloud Shell, DB VM 한 대의 인스턴스 메타데이터 | 기존 권한·공용 키를 바꾸지 않고 터널 접속 | 기존 SSH 메타데이터 부재를 확인한 뒤 만료형 키 1개 등록 | 승인된 VM 한 대에만 임시 접근 |
| Cloud Shell → DB VM, Patroni `/primary` | 터널 대상이 현재 primary인지 확인 | 한 후보는 HTTP **503**, 다른 후보는 HTTP **200** | HTTP 200 후보를 터널 대상으로 선택. SQL 세션의 `pg_is_in_recovery()` 확인은 아직 못 함 |
| Cloud Shell, `ssh -fN -L`과 `psql -w` | primary까지 네트워크·인증 경계를 분리 | `psql`이 서버의 암호 요구 단계까지 도달했고 `no password supplied`로 종료 | 네트워크 터널은 동작. 인증·Outbox SQL 성공은 아님 |
| 로컬 → Cloud Shell 파일 업로드 | 승인된 앱 DB 암호를 출력하지 않고 일시적으로 전달 | Chrome 확장의 로컬 파일 업로드 권한 제한으로 파일 선택 단계 실패. 실제 전송 0건 | 브라우저 보안 설정을 완화하지 않았고 암호를 Cloud Shell에 입력하지 않음 |
| Cloud Shell 및 로컬 정리 | 임시 접근·암호 잔존 방지 | SSH 터널 닫힘, VM `ssh-keys` 부재, Cloud Shell 개인키·키 디렉터리·암호 파일·암호 변수 부재, 로컬 임시 암호 파일 부재 확인 | 임시 접근 수단 회수 완료 |

집계 SQL은 **실행되지 않았으므로 결과 건수는 없다**. `PENDING=0`이나 Dispatcher 정상 소비로 해석하면 안 된다. 앱 A/B와 공용 캐시 VM은 기동하지 않았다. 이 실행은 앞선 게스트 사전 점검과 별도 기록이며, 실패를 정상 소비 결과로 합치지 않는다.

다음에는 승인된 방식으로 암호를 전달할 수 있는 경로를 먼저 확보하거나, 기존 비밀을 노출하지 않는 DB 조회 수단을 마련해야 한다. 그 후 같은 SQL 세션에서 `pg_is_in_recovery() = false`와 Outbox 집계를 확인하고, backlog가 안전할 때만 앱·Dispatcher 소비 시험을 검토한다.
